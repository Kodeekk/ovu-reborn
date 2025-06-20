package org.kodeekk

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import kotlinx.coroutines.*
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.block.VaultBlock
import net.minecraft.block.entity.VaultBlockEntity
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.state.property.Properties
import net.minecraft.text.Text
import net.minecraft.util.Hand
import net.minecraft.util.Identifier
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory.getLogger
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt


object Ovureborn : ModInitializer {
	private lateinit var scan_key_binding: KeyBinding
	private val logger = getLogger("OVU Reborn")
	private val vault_block = Registries.BLOCK.get(Identifier.of("minecraft", "vault")) as? VaultBlock
	private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default)

	private var past = mutableListOf<String>()
	private var target = "Heavy Core"
	private var sensitivity = 1
	private var scan_radius = 1

	private var is_target_ominous = true
	private var is_scanning = false

	private var calibration_array = mutableListOf<String>()
	private var calibration_success = false

	var OminousVaultSuggestionProvider: SuggestionProvider<ServerCommandSource?> =
		SuggestionProvider { context: CommandContext<ServerCommandSource?>?, builder: SuggestionsBuilder? ->
			val suggestions: List<String> = listOf(
				"Emerald",
				"Wind Charge",
				"Diamond",
				"Enchanted Golden Apple",
				"Flow Banner Pattern",
				"Ominous Bottle",
				"Block of Emerald",
				"Crossbow",
				"Block of Iron",
				"Golden Apple",
				"Diamond Axe",
				"Diamond Chestplate",
				"Music Disc",
				"Heavy Core",
				"Enchanted Book",
				"Block of Diamond",
			)
			for (entry in suggestions) {
				builder!!.suggest(entry)
			}
			builder!!.buildFuture()
		}
	var VaultSuggestionProvider: SuggestionProvider<ServerCommandSource?> =
		SuggestionProvider { context: CommandContext<ServerCommandSource?>?, builder: SuggestionsBuilder? ->
			val suggestions: List<String> = listOf(
				"Emerald",
				"Arrow",
				"Arrow of Poison",
				"Iron Ingot",
				"Wind Charge",
				"Honey Bottle",
				"Ominous Bottle",
				"Shield",
				"Bow",
				"Diamond",
				"Golden Apple",
				"Golden Carrot",
				"Enchanted Book",
				"Crossbow",
				"Iron Axe",
				"Iron Chestplate",
				"Bolt Armor Trim Smithing Template",
				"Music Disc",
				"Guster Banner Pattern",
				"Diamond Axe",
				"Diamond Chestplate",
				"Trident",
			)
			for (entry in suggestions) {
				builder!!.suggest(entry)
			}
			builder!!.buildFuture()
		}

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
					.then(CommandManager.literal("radius")
						.then(CommandManager.argument("value", IntegerArgumentType.integer(1, 100))
							.executes { context ->
								scan_radius = IntegerArgumentType.getInteger(context, "value")
								context.source.player?.sendMessage(Text.literal("§aSet radius to: §e$scan_radius"), true)
								1
							}
						)
					)
					.then(CommandManager.literal("target")
						.then(CommandManager.literal("regular")
							.then(CommandManager.argument("item", StringArgumentType.string())
								.suggests(VaultSuggestionProvider)
								.executes { context ->
									target = StringArgumentType.getString(context, "item")
									is_target_ominous = false
									context.source.player?.sendMessage(Text.literal("§aSet target item to: §e$target"), true)
									1
								}
							)
						)
						.then(CommandManager.literal("ominous")
							.then(CommandManager.argument("item", StringArgumentType.string())
								.suggests(OminousVaultSuggestionProvider)
								.executes { context ->
									target = StringArgumentType.getString(context, "item")
									is_target_ominous = true
									context.source.player?.sendMessage(Text.literal("§aSet target item to: §e$target"), true)
									1
								}
							)
						)
					)
					.then(CommandManager.literal("sensitivity")
						.then(CommandManager.argument("value", IntegerArgumentType.integer(1, 100))
							.executes { context ->
								sensitivity = IntegerArgumentType.getInteger(context, "value")
                                context.source.player?.sendMessage(Text.literal("§aSet sensitivity to: §e$sensitivity"), true)
								1
							}
						)
					)
					.then(CommandManager.literal("checkout")
						.executes { context ->
							context.source.player?.sendMessage(Text.literal("§aSummarizing:§e"))
							context.source.player?.sendMessage(Text.literal("§a\tTarget:§e $target"))
							context.source.player?.sendMessage(Text.literal("§a\tSensitivity:§e $sensitivity"))
							context.source.player?.sendMessage(Text.literal("§a\tScan Radius:§e $scan_radius"))
							context.source.player?.sendMessage(Text.literal("§a\tIs Target Ominous:§e $is_target_ominous"))
							context.source.player?.sendMessage(Text.literal("§a\tIs Scanning:§e $is_scanning"))
							context.source.player?.sendMessage(Text.literal("§a\tCalibration Array:§e $calibration_array"))
							context.source.player?.sendMessage(Text.literal("§a\tPast Items:§e $past"))
							1
						}
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

	private fun registerKeyBindings() {
		scan_key_binding = KeyBindingHelper.registerKeyBinding(
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
			if (scan_key_binding.wasPressed()) {
				is_scanning = !is_scanning
				calibration_array.clear()
			}
			if (is_scanning) {
				coroutineScope.launch {
					scanNearbyVaults(client, scan_radius)
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
				// player.sendMessage(Text.literal("§6Found §b${vaults.size}§6 vault(s) nearby:"), true)
				vaults.forEach { vault ->
					var itemName: String = ""
					if (
                        (vault.isOminous && is_target_ominous) || (!vault.isOminous && !is_target_ominous)
					) {
						itemName = vault.displayItem?.name?.string ?: "§8No item"

						if (itemName != "§8No item") {
							if ( !past.contains(itemName) ) past.add(itemName)
							if (calibration_array.size < sensitivity) { calibration_array.add(itemName) } else
								if (calibration_array.size == sensitivity) {
									calibration_success = calibration_array.all { it == target }
									if (itemName == target && calibration_success) {
										instantRightClick()
										calibration_success = false
										is_scanning = false
									}
									calibration_array.clear()
								}
						}
					}
					val message = Text.literal("§e${vault.position.x} ${vault.position.y} ${vault.position.z}§7: §f$itemName §7(§a${"%.1f".format(vault.distance)}m§7) (calibrated - §a${calibration_array.size}§7)")
					player.sendMessage(message, true)
				}
			}
		}
	}

	private suspend fun findVaultsInRadius(world: World, center: BlockPos, radius: Int): List<VaultInfo> =
        coroutineScope {
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
							val block = state.block

							if (block == vault_block) {
                                val displayItem = getVaultDisplayItem(world, pos)
                                val distance = getDistance(center, pos)
								val isOminous = state.get(VaultBlock.OMINOUS)

                                synchronized(vaults) {
                                    vaults.add(VaultInfo(pos, displayItem, distance, isOminous))
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
		val distance: Double,
		val isOminous: Boolean
	)
}