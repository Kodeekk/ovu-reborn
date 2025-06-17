package org.kodeekk.utils

import net.minecraft.block.entity.VaultBlockEntity
import net.minecraft.block.vault.VaultSharedData
import net.minecraft.item.ItemStack
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World

fun getDisplayItemSafe(world: World, pos: BlockPos): ItemStack? {
    return try {
        val blockEntity = world.getBlockEntity(pos) as? VaultBlockEntity ?: return null
        val sharedDataField = VaultBlockEntity::class.java.getDeclaredField("sharedData")
        sharedDataField.isAccessible = true
        val sharedData = sharedDataField.get(blockEntity) as VaultSharedData
        sharedData.displayItem.takeIf { !it.isEmpty }
    } catch (e: Exception) {
        println("Error getting display item: ${e.message}")
        null
    }
    //comment21
}

