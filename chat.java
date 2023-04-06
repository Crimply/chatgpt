package me.crimp.fusion.mod.modules.misc;

import com.google.common.collect.Lists;
import me.crimp.fusion.mod.modules.Module;
import me.crimp.fusion.mod.setting.Bind;
import me.crimp.fusion.mod.setting.Setting;
import me.crimp.fusion.utils.ColorUtil;
import me.crimp.fusion.utils.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemShulkerBox;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;


public class ShulkerPreview extends Module {
    private static final ResourceLocation SHULKER_GUI_TEXTURE = new ResourceLocation("textures/gui/container/shulker_box.png");
    public Setting<Bind> Keybind = register(new Setting<>("ShulkerPreviewBind", new Bind(Keyboard.KEY_B)));

    public ShulkerPreview() {
        super("ShulkerPreview", "Displays the contents of a shulker box when hovered over in a GUI", Category.Render, false, false);
    }

    public static ItemStack itemStack;
    public static int mouseX = 0;
    public static int mouseY = 0;
    private static final int SHULKER_GUI_SIZE = 16 + 54 + 6;

    private static final int CACHE_HOVERING_INDEX = 0;
    private static final int CACHE_HOLDING_INDEX = 1;
    private static final int CACHE_RESERVE_SIZE = 2;

    private final List<GuiShulkerViewer> guiCache =
            Lists.newArrayListWithExpectedSize(CACHE_RESERVE_SIZE);
    private final Lock cacheLock = new ReentrantLock();

    private boolean locked = false;
    private boolean updated = false;

    private boolean isMouseInShulkerGui = false;
    private boolean isModGeneratedToolTip = false;

    private int lastX = -1;
    private int lastY = -1;

    public static <T> boolean isInRange(Collection<T> list, int index) {
        return list != null && index >= 0 && index < list.size();
    }

    private boolean isLocked() {
        return locked && updated;
    }

    private boolean setInCache(int index, @Nullable GuiShulkerViewer viewer) {
        if (index < 0) {
            return false;
        } else if (viewer == null && index > (CACHE_RESERVE_SIZE - 1) && index == guiCache.size() - 1) {
            guiCache.remove(index); // remove non-reserved extras
            int previous = index - 1;
            if (previous > (CACHE_RESERVE_SIZE - 1)
                    && !getInCache(previous)
                    .isPresent()) // check if previous entry is null and remove it recursively if it is
            {
                return setInCache(previous, null);
            } else {
                return true;
            }
        } else if (index > guiCache.size() - 1) { // array not big enough
            for (int i = Math.max(guiCache.size(), 1); i < index; ++i) {
                guiCache.add(i, null); // fill with nulls up to the index
            }
            guiCache.add(index, viewer);
            return true;
        } else {
            guiCache.set(index, viewer);
            return true;
        }
    }

    private boolean appendInCache(@Nonnull GuiShulkerViewer viewer) {
        return setInCache(Math.max(guiCache.size() - 1, CACHE_RESERVE_SIZE), viewer);
    }

    private Optional<GuiShulkerViewer> getInCache(int index) {
        return isInRange(guiCache, index)
                ? Optional.ofNullable(guiCache.get(index))
                : Optional.empty();
    }

    private void clearCache() {
        for (int i = 0; i < CACHE_RESERVE_SIZE; ++i) {
            setInCache(
                    i, null); // set all reserve slots to null, and add them if they don't already exist
        }
        while (guiCache.size() > CACHE_RESERVE_SIZE) {
            setInCache(guiCache.size() - 1, null); // clear the rest
        }
    }

    public void reset() {
        boolean isKeySet = false;
        locked = updated = isKeySet = isMouseInShulkerGui = isModGeneratedToolTip = false;
        lastX = lastY = -1;
        clearCache();
    }

    public static List<ItemStack> getShulkerContents(ItemStack stack) { // TODO: move somewhere else
        NonNullList<ItemStack> contents = NonNullList.withSize(27, ItemStack.EMPTY);
        NBTTagCompound compound = stack.getTagCompound();
        if (compound != null && compound.hasKey("BlockEntityTag", 10)) {
            NBTTagCompound tags = compound.getCompoundTag("BlockEntityTag");
            if (tags.hasKey("Items", 9)) {
                // load in the items
                ItemStackHelper.loadAllItems(tags, contents);
            }
        }
        return contents;
    }

    private GuiShulkerViewer newShulkerGui(ItemStack parentShulker, int priority) {
        return new GuiShulkerViewer(
                new ShulkerContainer(new ShulkerInventory(getShulkerContents(parentShulker)), 27), parentShulker, priority);
    }


    @Override
    public void onEnable() {
        cacheLock.lock();
        try {
            reset();
        } finally {
            cacheLock.unlock();
        }
    }



    @SubscribeEvent
    public void onKeyboardInput(GuiScreenEvent.KeyboardInputEvent event) {
        locked = Keyboard.isKeyDown(Keybind.getValue().getKey());
    }

    @SubscribeEvent
    public void onPreTooptipRender(RenderTooltipEvent.Pre event) {
        if (!(mc.currentScreen instanceof GuiContainer) || isModGeneratedToolTip) {
            return;
        }

        if (isMouseInShulkerGui) {
            // do not render tool tips that are inside the region of our shulker gui
            event.setCanceled(true);
        } else if (event.getStack().getItem() instanceof ItemShulkerBox) {
            event.setCanceled(true); // do not draw normal tool tip
        }
    }

    @SubscribeEvent
    public void onGuiChanged(GuiOpenEvent event) {
        if (event.getGui() == null) {
            reset();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onRender(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (mc.currentScreen instanceof GuiContainer) {
            cacheLock.lock();
            try {
                GuiContainer gui = (GuiContainer) event.getGui();

                if (!isLocked()) {
                    // show stats for the item being hovered over
                    Slot slotUnder = gui.getSlotUnderMouse();
                    if (slotUnder == null
                            || !slotUnder.getHasStack()
                            || slotUnder.getStack().isEmpty()
                            || !(slotUnder.getStack().getItem() instanceof ItemShulkerBox)) {
                        setInCache(CACHE_HOVERING_INDEX, null);
                    } else if (!ItemStack.areItemStacksEqual(
                            (ItemStack) getInCache(1).map(GuiShulkerViewer::getParentShulker).orElse(ItemStack.EMPTY),
                            slotUnder.getStack())) {
                        setInCache(CACHE_HOVERING_INDEX, newShulkerGui(slotUnder.getStack(), 1));
                    }

                    // show stats for held item
                    ItemStack stackHeld = mc.player.inventory.getItemStack();
                    if (stackHeld.isEmpty() || !(stackHeld.getItem() instanceof ItemShulkerBox)) {
                        setInCache(CACHE_HOLDING_INDEX, null);
                    } else if (!ItemStack.areItemStacksEqual(
                            (ItemStack) getInCache(1).map(GuiShulkerViewer::getParentShulker).orElse(ItemStack.EMPTY),
                            stackHeld)) {
                        setInCache(CACHE_HOLDING_INDEX, newShulkerGui(stackHeld, 0));
                    }

                    if (locked && !updated && guiCache.stream().anyMatch(Objects::nonNull)) {
                        updated = true;
                    }
                }

                int renderX;
                int renderY;
                if (!isLocked() || (lastX == -1 && lastY == -1)) {
                    int count = (int) guiCache.stream().filter(Objects::nonNull).count();
                    int x_offset = 8;
                    int y_offset = 0;
                    renderX = lastX = event.getMouseX() + x_offset;
                    renderY = lastY = event.getMouseY() - (SHULKER_GUI_SIZE * count) / 2 + y_offset;
                } else {
                    renderX = lastX;
                    renderY = lastY;
                }

                isMouseInShulkerGui = false; // recheck

                // Add null check here
                if (guiCache != null) {
                    for (GuiShulkerViewer ui : guiCache) {
                        ui.posX = renderX;
                        ui.posY = renderY;
                        ui.drawScreen(event.getMouseX(), event.getMouseY(), event.getRenderPartialTicks());
                        renderY = renderY + SHULKER_GUI_SIZE + 1;
                    }
                }

            } finally {
                cacheLock.unlock();
            }
            GlStateManager.enableDepth();
            GlStateManager.enableLighting();
            GlStateManager.color(1.f, 1.f, 1.f, 1.0f);
        }
    }

    class GuiShulkerViewer extends GuiContainer implements Comparable<GuiShulkerViewer> {

        private final ItemStack parentShulker;
        private final int priority;

        public int posX = 0;
        public int posY = 0;

        public GuiShulkerViewer(Container inventorySlotsIn, ItemStack parentShulker, int priority) {
            super(inventorySlotsIn);
            this.parentShulker = parentShulker;
            this.priority = priority;
            this.width = mc.displayWidth;
            this.height = mc.displayHeight;
            this.xSize = 176;
            this.ySize = SHULKER_GUI_SIZE;

        }


        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            final int DEPTH = 500;

            int x = posX;
            int y = posY;

            GlStateManager.enableTexture2D();
            GlStateManager.disableLighting();
//            JsonArray tooltip_opacity = null;
//            JsonArray locked_opacity = null;
//            GlStateManager.color(
//                    1.f,
//                    1.f,
//                    1.f,
//                    !isLocked()
//                            ? (tooltip_opacity.getAsFloat() / 255.f)
//                            : (locked_opacity.getAsFloat() / 255.f));

            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                    GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE,
                    GlStateManager.DestFactor.ZERO);

            mc.getTextureManager().bindTexture(SHULKER_GUI_TEXTURE);

            // width 176        = width of container
            // height 16        = top of the gui
            // height 54        = gui item boxes
            // height 6         = bottom of the gui
            RenderUtil.drawTexturedRect(x, y, 0, 0, 176, 16, DEPTH);
            RenderUtil.drawTexturedRect(x, y + 16, 0, 16, 176, 54, DEPTH);
            RenderUtil.drawTexturedRect(x, y + 16 + 54, 0, 160, 176, 6, DEPTH);

            GlStateManager.disableDepth();
            mc.fontRenderer.drawStringWithShadow(parentShulker.getDisplayName(), x + 8, y + 6, ColorUtil.toRGBA(255,255,255,255));
            GlStateManager.enableDepth();

            RenderHelper.enableGUIStandardItemLighting();
            GlStateManager.enableRescaleNormal();
            GlStateManager.enableColorMaterial();
            GlStateManager.enableLighting();

            Slot hoveringOver = null;

            int rx = x + 8;
            int ry = y - 1;

            for (Slot slot : inventorySlots.inventorySlots) {
                if (slot.getHasStack()) {
                    int px = rx + slot.xPos;
                    int py = ry + slot.yPos;
                    mc.getRenderItem().zLevel = DEPTH + 1;
                    //drawitem(slot.getStack(), px, py);
                    renderItemOverlayIntoGUI(slot.getStack(), px, py,null);
                    mc.getRenderItem().zLevel = 0.f;
                    if (isPointInRegion(px, py, 16, 16, mouseX, mouseY)) {
                        hoveringOver = slot;
                    }
                }
            }

            GlStateManager.disableLighting();

            if (hoveringOver != null) {
                // background of the gui
                GlStateManager.disableLighting();
                GlStateManager.disableDepth();
                GlStateManager.colorMask(true, true, true, false);
                this.drawGradientRect(
                        rx + hoveringOver.xPos,
                        ry + hoveringOver.yPos,
                        rx + hoveringOver.xPos + 16,
                        ry + hoveringOver.yPos + 16,
                        -2130706433,
                        -2130706433);
                GlStateManager.colorMask(true, true, true, true);

                // tool tip
                GlStateManager.color(1.f, 1.f, 1.f, 1.0f);
                GlStateManager.pushMatrix();
                isModGeneratedToolTip = true;
                renderToolTip(hoveringOver.getStack(), mouseX + 8, mouseY + 8);
                isModGeneratedToolTip = false;
                GlStateManager.popMatrix();
                GlStateManager.enableDepth();
            }

            if (isPointInRegion(this.posX, this.posY, this.width, this.height, mouseX, mouseY)) {
                isMouseInShulkerGui = true;
            }

            GlStateManager.disableBlend();
            GlStateManager.color(1.f, 1.f, 1.f, 1.0f);
        }

        @Override
        protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        }

        @Override
        public int compareTo(GuiShulkerViewer o) {
            return Integer.compare(priority, o.priority);
        }

        public <U> U getParentShulker() {
            return (U) this.parentShulker;
        }
    }

    static class ShulkerContainer extends Container {

        public ShulkerContainer(ShulkerInventory inventory, int size) {
            for (int i = 0; i < size; ++i) {
                int x = i % 9 * 18;
                int y = ((i / 9 + 1) * 18) + 1;
                addSlotToContainer(new Slot(inventory, i, x, y));
            }
        }

        @Override
        public boolean canInteractWith(EntityPlayer playerIn) {
            return false;
        }
    }

    static class ShulkerInventory implements IInventory {

        private final List<ItemStack> contents;

        public ShulkerInventory(List<ItemStack> contents) {
            this.contents = contents;
        }

        @Override
        public int getSizeInventory() {
            return contents.size();
        }

        @Override
        public boolean isEmpty() {
            return contents.isEmpty();
        }

        @Override
        public ItemStack getStackInSlot(int index) {
            return contents.get(index);
        }

        @Override
        public ItemStack decrStackSize(int index, int count) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ItemStack removeStackFromSlot(int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setInventorySlotContents(int index, ItemStack stack) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getInventoryStackLimit() {
            return 27;
        }

        @Override
        public void markDirty() {
        }

        @Override
        public boolean isUsableByPlayer(EntityPlayer player) {
            return false;
        }

        @Override
        public void openInventory(EntityPlayer player) {
        }

        @Override
        public void closeInventory(EntityPlayer player) {
        }

        @Override
        public boolean isItemValidForSlot(int index, ItemStack stack) {
            return index > 0 && index < contents.size() && contents.get(index).equals(stack);
        }

        @Override
        public int getField(int id) {
            return 0;
        }

        @Override
        public void setField(int id, int value) {
        }

        @Override
        public int getFieldCount() {
            return 0;
        }

        @Override
        public void clear() {
        }

        @Override
        public String getName() {
            return "";
        }

        @Override
        public boolean hasCustomName() {
            return false;
        }

        @Override
        public ITextComponent getDisplayName() {
            return new TextComponentString("");
        }
    }
    public void renderItemOverlayIntoGUI(ItemStack stack, int xPosition, int yPosition, @Nullable String text) {
        if (!stack.isEmpty()) {
            if (stack.getCount() != 1 || text != null) {
                String s = text == null ? String.valueOf(stack.getCount()) : text;
                GlStateManager.disableLighting();
                GlStateManager.disableDepth();
                GlStateManager.disableBlend();
                mc.fontRenderer.drawStringWithShadow(s, (float) (xPosition + 19 - 2 - mc.fontRenderer.getStringWidth(s)), (float) (yPosition + 6 + 3) - 2, 16777215);
                GlStateManager.enableLighting();
                GlStateManager.enableDepth();
                // Fixes opaque cooldown overlay a bit lower
                // TODO: check if enabled blending still screws things up down the line.
                GlStateManager.enableBlend();
            }

            if (stack.isItemDamaged()) {
                GlStateManager.disableLighting();
                GlStateManager.disableDepth();
                GlStateManager.disableTexture2D();
                GlStateManager.disableAlpha();
                GlStateManager.disableBlend();
                Tessellator tessellator = Tessellator.getInstance();
                BufferBuilder bufferbuilder = tessellator.getBuffer();
                double health = getDurabilityForDisplay(stack);
                int rgbfordisplay = getRGBDurabilityForDisplay(stack);
                int i = Math.round(13.0F - (float) health * 13.0F);
                int j = rgbfordisplay;
                this.draw(bufferbuilder, xPosition + 2, yPosition + 13, 13, 2, 0, 0, 0, 255);
                this.draw(bufferbuilder, xPosition + 2, yPosition + 13, i, 1, j >> 16 & 255, j >> 8 & 255, j & 255, 255);
                GlStateManager.enableBlend();
                GlStateManager.enableAlpha();
                GlStateManager.enableTexture2D();
                GlStateManager.enableLighting();
                GlStateManager.enableDepth();
            }

            EntityPlayerSP entityplayersp = Minecraft.getMinecraft().player;
            float f3 = entityplayersp == null ? 0.0F : entityplayersp.getCooldownTracker().getCooldown(stack.getItem(), Minecraft.getMinecraft().getRenderPartialTicks());

            if (f3 > 0.0F) {
                GlStateManager.disableLighting();
                GlStateManager.disableDepth();
                GlStateManager.disableTexture2D();
                Tessellator tessellator1 = Tessellator.getInstance();
                BufferBuilder bufferbuilder1 = tessellator1.getBuffer();
                this.draw(bufferbuilder1, xPosition, yPosition + MathHelper.floor(16.0F * (1.0F - f3)), 16, MathHelper.ceil(16.0F * f3), 255, 255, 255, 127);
                GlStateManager.enableTexture2D();
                GlStateManager.enableLighting();
                GlStateManager.enableDepth();
            }
        }
    }

    private void draw(BufferBuilder renderer, int x, int y, int width, int height, int red, int green, int blue, int alpha) {
        renderer.begin(7, DefaultVertexFormats.POSITION_COLOR);
        renderer.pos(x + 0, y + 0, 0.0D).color(red, green, blue, alpha).endVertex();
        renderer.pos(x + 0, y + height, 0.0D).color(red, green, blue, alpha).endVertex();
        renderer.pos(x + width, y + height, 0.0D).color(red, green, blue, alpha).endVertex();
        renderer.pos(x + width, y + 0, 0.0D).color(red, green, blue, alpha).endVertex();
        Tessellator.getInstance().draw();
    }


    public double getDurabilityForDisplay(ItemStack stack) {
        return (double) stack.getItemDamage() / (double) stack.getMaxDamage();
    }


    public int getRGBDurabilityForDisplay(ItemStack stack) {
        return MathHelper.hsvToRGB(Math.max(0.0F, (float) (1.0D - this.getDurabilityForDisplay(stack))) / 3.0F, 1.0F, 1.0F);
    }
}