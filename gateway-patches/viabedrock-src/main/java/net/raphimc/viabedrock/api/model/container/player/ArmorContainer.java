/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.api.model.container.player;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.item.Item;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;

import java.util.Arrays;

public class ArmorContainer extends InventorySubContainer {

    /**
     * Bedrock's armor container gained a fifth "Body" slot (SharedTypes_Legacy_ArmorSlot.Body).
     * Java has no player body slot, so the item is kept server-side and never sent to the client.
     */
    public static final int BODY_SLOT = 4;
    private static final int JAVA_SIZE = 4;

    public ArmorContainer(final UserConnection user) {
        super(user, (byte) ContainerID.CONTAINER_ID_ARMOR.getValue(), ContainerType.ARMOR, BODY_SLOT + 1);
    }

    @Override
    public int javaSlot(final int slot) {
        return 5 + slot;
    }

    @Override
    public boolean isJavaVisible(final int slot) {
        return slot < JAVA_SIZE;
    }

    @Override
    public Item[] getJavaItems() {
        final Item[] javaItems = this.user.get(ItemRewriter.class).javaItems(this.items);
        return Arrays.copyOf(javaItems, JAVA_SIZE);
    }

    @Override
    public Item getJavaItem(final int slot) {
        if (!this.isJavaVisible(slot)) {
            return null;
        }
        return this.user.get(ItemRewriter.class).javaItem(this.getItem(slot));
    }

}
