package org.kodeekk

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import kotlinx.coroutines.*
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.block.Block
import net.minecraft.block.VaultBlock
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.server.command.CommandManager
import net.minecraft.text.Text
import net.minecraft.util.Hand
import net.minecraft.util.Identifier
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory.getLogger
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

object Ovureborn : ModInitializer {
	private val logger = getLogger("OVU Reborn")
	private lateinit var scanKeyBinding: KeyBinding
	private val VAULT_BLOCK = Registries.BLOCK.get(Identifier.of("minecraft", "vault")) as? VaultBlock
	private val coroutineScope = CoroutineScope(Dispatchers.Default)
	// Configuration
	private var target = ""
	private var sensitivity = 0
	private var calibration_array = mutableListOf<String>()
	private var calibration_success = false
	private const val SCAN_RADIUS = 5
	private var isScanning = false

	override fun onInitialize() {
		logger.info("OVU HAS ARRIVED!")
		registerCommand()
		registerKeyBindings()
		registerTickHandler()
	}

	fun registerCommand() {
		CommandRegistrationCallback.EVENT.register { dispatcher, registryAccess, environment ->
			dispatcher.register(
				CommandManager.literal("ovu")
					.then(CommandManager.literal("target")
						.then(CommandManager.argument("item", StringArgumentType.string())
							.executes { context ->
								target = StringArgumentType.getString(context, "item")
								context.source.sendMessage(Text.literal("§aSet target item to: §e$target"))
								1
							}
						)
					)
					.then(CommandManager.literal("sensitivity")
						.then(CommandManager.argument("value", IntegerArgumentType.integer(1, 100))
							.executes { context ->
								sensitivity = IntegerArgumentType.getInteger(context, "value")
								context.source.sendMessage(Text.literal("§aSet sensitivity to: §e$sensitivity"))
								1
							}
						)
					)
			)
		}
	}

	fun instantRightClick() {
		val client = MinecraftClient.getInstance()

		client.execute {
			client.interactionManager?.interactItem(client.player, Hand.MAIN_HAND)

			val hitResult = client.crosshairTarget

			if (hitResult is BlockHitResult) {
				client.interactionManager?.interactBlock(
					client.player,
					Hand.MAIN_HAND,
					hitResult
				)
			}
		}
	}

//	fun calibrate(newElement: String): Boolean {
//		calibration_array[calibration_currentIndex++] = newElement
//
//		if (calibration_currentIndex >= sensitivity) {
//			val allMatchTarget = calibration_array.all { it == target }
//			calibration_array = arrayOfNulls(sensitivity)
//			calibration_currentIndex = 0
//			return allMatchTarget
//		}
//		return false
//	}

	private fun registerKeyBindings() {
		scanKeyBinding = KeyBindingHelper.registerKeyBinding(
			KeyBinding(
				"key.ovureborn.scan_vaults",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_V,
				"category.ovureborn"
			)
		)
	}

	private fun registerTickHandler() {
		ClientTickEvents.END_CLIENT_TICK.register { client ->
			if (scanKeyBinding.wasPressed()) {
				isScanning = !isScanning
				calibration_array.clear()
			}
			if (isScanning) {
				coroutineScope.launch {
					scanNearbyVaults(client, SCAN_RADIUS)
				}
			}
		}
	}

	private suspend fun scanNearbyVaults(client: MinecraftClient, radius: Int) = withContext(Dispatchers.Default) {
		val world = client.world ?: return@withContext
		val player = client.player ?: return@withContext
		val vaults = findVaultsInRadius(world, player.blockPos, radius)

		withContext(Dispatchers.IO) {
			if (vaults.isNotEmpty()) {
//				player.sendMessage(Text.literal("§6Found §b${vaults.size}§6 vault(s) nearby:"), true)
				vaults.forEach { vault ->
					val itemName = vault.displayItem?.name?.string ?: "§8No item"

					if (itemName != "§8No item") {
						if (calibration_array.size < sensitivity) { calibration_array.add(itemName) } else
						if (calibration_array.size == sensitivity) {
							calibration_success = calibration_array.all { it == target }
							if (itemName == target && calibration_success) {
								instantRightClick()
								calibration_success = false
								isScanning = false
								logger.info("Clicking on $itemName when calibration gave $calibration_array")
							}
							calibration_array.clear()
						}
					}
					val message = Text.literal("§e${vault.position.x} ${vault.position.y} ${vault.position.z}§7: §f$itemName §7(§a${"%.1f".format(vault.distance)}m§7) (calibrated - §a${calibration_array.size}§7)")
					player.sendMessage(message, true)
				}
			}
		}
	}

	private suspend fun findVaultsInRadius(world: World, center: BlockPos, radius: Int): List<VaultInfo> = coroutineScope {
		val vaults = mutableListOf<VaultInfo>()
		val minX = center.x - radius
		val minY = center.y - radius
		val minZ = center.z - radius
		val maxX = center.x + radius
		val maxY = center.y + radius
		val maxZ = center.z + radius

		(minY..maxY).map { y ->
			async {
				for (x in minX..maxX) {
					for (z in minZ..maxZ) {
						val pos = BlockPos(x, y, z)
						val state = world.getBlockState(pos)

						if (state.block == VAULT_BLOCK) {
							val displayItem = getVaultDisplayItem(world, pos)
							val distance = getDistance(center, pos)
							synchronized(vaults) {
								vaults.add(VaultInfo(pos, displayItem, distance))
							}
						}
					}
				}
			}
		}.awaitAll()

		vaults.sortedBy { it.distance }
	}

	private fun getVaultDisplayItem(world: World, pos: BlockPos): ItemStack? {
		return try {
			val blockEntity = world.getBlockEntity(pos) ?: return null
			val sharedDataField = blockEntity.javaClass.getDeclaredField("sharedData")
			sharedDataField.isAccessible = true
			val sharedData = sharedDataField.get(blockEntity)
			val displayItemField = sharedData.javaClass.getDeclaredField("displayItem")
			displayItemField.isAccessible = true
			val item = displayItemField.get(sharedData) as ItemStack
			item.takeIf { !it.isEmpty }
		} catch (e: Exception) {
			logger.error("Error getting vault display item: ${e.message}")
			null
		}
	}

	private fun getDistance(pos1: BlockPos, pos2: BlockPos): Double {
		return sqrt(
			abs(pos1.x - pos2.x).toDouble().pow(2) +
					abs(pos1.y - pos2.y).toDouble().pow(2) +
					abs(pos1.z - pos2.z).toDouble().pow(2)
		)
	}

	private data class VaultInfo(
		val position: BlockPos,
		val displayItem: ItemStack?,
		val distance: Double
	)
}