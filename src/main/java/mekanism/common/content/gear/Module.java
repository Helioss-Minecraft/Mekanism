package mekanism.common.content.gear;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import mcp.MethodsReturnNonnullByDefault;
import mekanism.api.Action;
import mekanism.api.NBTConstants;
import mekanism.api.energy.IEnergyContainer;
import mekanism.api.gear.ICustomModule;
import mekanism.api.gear.IHUDElement;
import mekanism.api.gear.IModule;
import mekanism.api.gear.ModuleData;
import mekanism.api.gear.config.IModuleConfigItem;
import mekanism.api.gear.config.ModuleBooleanData;
import mekanism.api.gear.config.ModuleConfigData;
import mekanism.api.gear.config.ModuleConfigItemCreator;
import mekanism.api.inventory.AutomationType;
import mekanism.api.math.FloatingLong;
import mekanism.api.text.EnumColor;
import mekanism.api.text.IHasTextComponent;
import mekanism.api.text.ILangEntry;
import mekanism.common.MekanismLang;
import mekanism.common.content.gear.ModuleConfigItem.DisableableModuleConfigItem;
import mekanism.common.util.ItemDataUtils;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.StorageUtils;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.Util;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.common.util.Constants.NBT;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public final class Module<MODULE extends ICustomModule<MODULE>> implements IModule<MODULE> {

    public static final String ENABLED_KEY = "enabled";
    public static final String HANDLE_MODE_CHANGE_KEY = "handleModeChange";

    protected final List<ModuleConfigItem<?>> configItems = new ArrayList<>();

    private final ModuleData<MODULE> data;
    private final ItemStack container;
    private final MODULE customModule;

    private ModuleConfigItem<Boolean> enabled;
    private ModuleConfigItem<Boolean> handleModeChange;
    private ModuleConfigItem<Boolean> renderHUD;

    private int installed = 1;

    public Module(ModuleData<MODULE> data, ItemStack container) {
        this.data = data;
        this.container = container;
        this.customModule = data.get();
    }

    @Override
    public MODULE getCustomInstance() {
        return customModule;
    }

    public void init() {
        enabled = addConfigItem(new ModuleConfigItem<>(this, ENABLED_KEY, MekanismLang.MODULE_ENABLED, new ModuleBooleanData(!data.isDisabledByDefault())));
        if (data.handlesModeChange()) {
            handleModeChange = addConfigItem(new ModuleConfigItem<>(this, HANDLE_MODE_CHANGE_KEY, MekanismLang.MODULE_HANDLE_MODE_CHANGE, new ModuleBooleanData()));
        }
        if (data.rendersHUD()) {
            renderHUD = addConfigItem(new ModuleConfigItem<>(this, "renderHUD", MekanismLang.MODULE_RENDER_HUD, new ModuleBooleanData()));
        }
        customModule.init(this, new ModuleConfigItemCreator() {
            @Override
            public <TYPE> IModuleConfigItem<TYPE> createConfigItem(String name, ILangEntry description, ModuleConfigData<TYPE> data) {
                return addConfigItem(new ModuleConfigItem<>(Module.this, name, description, data));
            }

            @Override
            public IModuleConfigItem<Boolean> createDisableableConfigItem(String name, ILangEntry description, boolean def, BooleanSupplier isConfigEnabled) {
                return addConfigItem(new DisableableModuleConfigItem(Module.this, name, description, def, isConfigEnabled));
            }
        });
    }

    private <T> ModuleConfigItem<T> addConfigItem(ModuleConfigItem<T> item) {
        configItems.add(item);
        return item;
    }

    public void tick(PlayerEntity player) {
        if (isEnabled()) {
            if (player.level.isClientSide()) {
                customModule.tickClient(this, player);
            } else {
                customModule.tickServer(this, player);
            }
        }
    }

    @Nullable
    @Override
    public IEnergyContainer getEnergyContainer() {
        return StorageUtils.getEnergyContainer(getContainer(), 0);
    }

    @Override
    public FloatingLong getContainerEnergy() {
        IEnergyContainer energyContainer = getEnergyContainer();
        return energyContainer == null ? FloatingLong.ZERO : energyContainer.getEnergy();
    }

    @Override
    public boolean canUseEnergy(LivingEntity wearer, FloatingLong energy) {
        //TODO - 10.1: Decide if this should return true or false when in creative by default (update docs afterwards)
        return canUseEnergy(wearer, energy, true);
    }

    @Override
    public boolean canUseEnergy(LivingEntity wearer, FloatingLong energy, boolean checkCreative) {
        return canUseEnergy(wearer, getEnergyContainer(), energy, checkCreative);
    }

    @Override
    public boolean canUseEnergy(LivingEntity wearer, @Nullable IEnergyContainer energyContainer, FloatingLong energy, boolean checkCreative) {
        if (energyContainer != null && validEntity(wearer, checkCreative)) {
            return energyContainer.extract(energy, Action.SIMULATE, AutomationType.MANUAL).equals(energy);
        }
        return false;
    }

    @Override
    public FloatingLong useEnergy(LivingEntity wearer, FloatingLong energy) {
        return useEnergy(wearer, energy, true);
    }

    @Override
    public FloatingLong useEnergy(LivingEntity wearer, FloatingLong energy, boolean checkCreative) {
        return useEnergy(wearer, getEnergyContainer(), energy, checkCreative);
    }

    @Override
    public FloatingLong useEnergy(LivingEntity wearer, @Nullable IEnergyContainer energyContainer, FloatingLong energy, boolean checkCreative) {
        if (energyContainer != null && validEntity(wearer, checkCreative)) {
            return energyContainer.extract(energy, Action.EXECUTE, AutomationType.MANUAL);
        }
        return FloatingLong.ZERO;
    }

    private boolean validEntity(LivingEntity wearer, boolean checkCreative) {
        return !checkCreative || !(wearer instanceof PlayerEntity) || MekanismUtils.isPlayingMode((PlayerEntity) wearer);
    }

    public final void read(CompoundNBT nbt) {
        if (nbt.contains(NBTConstants.AMOUNT, NBT.TAG_INT)) {
            installed = nbt.getInt(NBTConstants.AMOUNT);
        }
        init();
        for (ModuleConfigItem<?> item : configItems) {
            item.read(nbt);
        }
    }

    /**
     * Save this module on the container ItemStack. Will create proper NBT structure if it does not yet exist.
     *
     * @param callback - will run after the NBT data is saved
     */
    public final void save(@Nullable Consumer<ItemStack> callback) {
        CompoundNBT modulesTag = ItemDataUtils.getCompound(container, NBTConstants.MODULES);
        String registryName = data.getRegistryName().toString();
        CompoundNBT nbt;
        String legacyName = data.getLegacyName();
        if (legacyName == null) {
            //If there is no legacy name just try to grab the module
            nbt = modulesTag.getCompound(registryName);
        }
        //TODO - 1.17: Remove this as we will be able to get rid of the legacy name
        //If there is a legacy name start by seeing if we have a compound for the proper name
        else if (modulesTag.contains(registryName, NBT.TAG_COMPOUND)) {
            //If we do, grab it
            nbt = modulesTag.getCompound(registryName);
        }
        //If we don't see if we have the legacy name stored
        else if (modulesTag.contains(legacyName, NBT.TAG_COMPOUND)) {
            //If we do grab it and remove it from the data the old legacy version
            nbt = modulesTag.getCompound(legacyName);
            modulesTag.remove(legacyName);
        } else {
            //If we don't have a legacy name stored just do a new compound
            nbt = new CompoundNBT();
        }

        nbt.putInt(NBTConstants.AMOUNT, installed);
        for (ModuleConfigItem<?> item : configItems) {
            item.write(nbt);
        }

        modulesTag.put(registryName, nbt);
        ItemDataUtils.setCompound(container, NBTConstants.MODULES, modulesTag);

        if (callback != null) {
            callback.accept(container);
        }
    }

    @Override
    public ModuleData<MODULE> getData() {
        return data;
    }

    @Override
    public int getInstalledCount() {
        return installed;
    }

    public void setInstalledCount(int installed) {
        this.installed = installed;
    }

    @Override
    public boolean isEnabled() {
        return enabled.get();
    }

    public void setDisabledForce() {
        enabled.getData().set(false);
        save(null);
    }

    @Override
    public ItemStack getContainer() {
        return container;
    }

    public List<ModuleConfigItem<?>> getConfigItems() {
        return configItems;
    }

    public void addHUDStrings(List<ITextComponent> list) {
        customModule.addHUDStrings(this, list::add);
    }

    public void addHUDElements(List<IHUDElement> list) {
        customModule.addHUDElements(this, list::add);
    }

    public void changeMode(@Nonnull PlayerEntity player, @Nonnull ItemStack stack, int shift, boolean displayChangeMessage) {
        customModule.changeMode(this, player, stack, shift, displayChangeMessage);
    }

    @Override
    public boolean handlesModeChange() {
        return data.handlesModeChange() && handleModeChange.get();
    }

    public void setModeHandlingDisabledForce() {
        if (data.handlesModeChange()) {
            handleModeChange.getData().set(false);
            save(null);
        }
    }

    @Override
    public boolean renderHUD() {
        return data.rendersHUD() && renderHUD.get();
    }

    public void onAdded(boolean first) {
        for (Module<?> module : ModuleHelper.INSTANCE.loadAll(getContainer())) {
            if (module.getData() != getData()) {
                // disable other exclusive modules if this is an exclusive module, as this one will now be active
                if (getData().isExclusive() && module.getData().isExclusive()) {
                    module.setDisabledForce();
                }
                if (handlesModeChange() && module.handlesModeChange()) {
                    module.setModeHandlingDisabledForce();
                }
            }
        }
        customModule.onAdded(this, first);
    }

    public void onRemoved(boolean last) {
        customModule.onRemoved(this, last);
    }

    @Override
    public void displayModeChange(PlayerEntity player, ITextComponent modeName, IHasTextComponent mode) {
        player.sendMessage(MekanismUtils.logFormat(MekanismLang.MODULE_MODE_CHANGE.translate(modeName, EnumColor.INDIGO, mode)), Util.NIL_UUID);
    }

    @Override
    public void toggleEnabled(PlayerEntity player, ITextComponent modeName) {
        enabled.set(!isEnabled(), null);
        ITextComponent message;
        if (isEnabled()) {
            message = MekanismLang.GENERIC_STORED.translate(modeName, EnumColor.BRIGHT_GREEN, MekanismLang.MODULE_ENABLED_LOWER);
        } else {
            message = MekanismLang.GENERIC_STORED.translate(modeName, EnumColor.DARK_RED, MekanismLang.MODULE_DISABLED_LOWER);
        }
        player.sendMessage(MekanismUtils.logFormat(message), Util.NIL_UUID);
    }
}
