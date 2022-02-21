package io.github.theepicblock.polymc.mixins.wizards.block;

import io.github.theepicblock.polymc.api.PolyMap;
import io.github.theepicblock.polymc.api.block.BlockPoly;
import io.github.theepicblock.polymc.api.misc.PolyMapProvider;
import io.github.theepicblock.polymc.api.wizard.Wizard;
import io.github.theepicblock.polymc.api.wizard.WizardView;
import io.github.theepicblock.polymc.impl.Util;
import io.github.theepicblock.polymc.impl.misc.PolyMapMap;
import io.github.theepicblock.polymc.impl.misc.WatchListener;
import io.github.theepicblock.polymc.impl.mixin.WizardTickerDuck;
import io.github.theepicblock.polymc.impl.poly.wizard.PlacedWizardInfo;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.collection.PackedIntegerArray;
import net.minecraft.util.collection.PaletteStorage;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.*;
import net.minecraft.world.gen.chunk.BlendingData;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("unchecked")
@Mixin(WorldChunk.class)
public abstract class WorldChunkMixin extends Chunk implements WatchListener, WizardView {
    @Unique
    private final PolyMapMap<Map<BlockPos,Wizard>> wizards = new PolyMapMap<>(this::createWizardsForChunk);
    @Unique
    private final ArrayList<ServerPlayerEntity> players = new ArrayList<>();

    @Shadow @Final World world;

    public WorldChunkMixin(ChunkPos pos, UpgradeData upgradeData, HeightLimitView heightLimitView, Registry<Biome> biome, long inhabitedTime, @Nullable ChunkSection[] sectionArrayInitializer, @Nullable BlendingData blendingData) {
        super(pos, upgradeData, heightLimitView, biome, inhabitedTime, sectionArrayInitializer, blendingData);
    }

    @Shadow public abstract World getWorld();

    @Unique
    private Map<BlockPos,Wizard> createWizardsForChunk(PolyMap map) {
        Map<BlockPos,Wizard> ret = new HashMap<>();
        if (!(this.world instanceof ServerWorld))
            return ret; //Wizards are only passed ServerWorlds, so we can't create any wizards here.
        if (!map.hasBlockWizards())
            return ret;

        for (ChunkSection section : this.sectionArray) {
            if (section == null) continue;

            PalettedContainer<BlockState> container = section.getBlockStateContainer();
            var data = ((PalettedContainerAccessor<BlockState>)container).getData();
            var palette = data.palette();
            var paletteData = data.storage();
            processWizards(map, palette, paletteData, section.getYOffset(), ret);
        }

        return ret;
    }

    @Unique
    private void processWizards(PolyMap polyMap, Palette<BlockState> palette, PaletteStorage data, int yOffset, Map<BlockPos,Wizard> wizardMap) {
        if (data.getSize() == 0) return;

        if (palette.getSize() < 64) {
            // The palette contains all block states present in the chunk
            var idsWithPolys = new BlockPoly[palette.getSize()];
            for (int i = 0; i < palette.getSize(); i++) {
                var state = palette.get(i);
                var poly = polyMap.getBlockPoly(state.getBlock());
                if (poly != null && poly.hasWizard()) {
                    idsWithPolys[i] = poly;
                }
            }

            if (data instanceof PackedIntegerArray) {
                // Fast way of iterating the packed data with an index
                int i = 0;

                var elementBits = data.getElementBits();
                var elementsPerLong = (char)(64 / elementBits);
                var maxValue = (1L << elementBits) - 1L;
                var size = data.getSize();

                data:
                for (long l : data.getData()) {
                    for (int j = 0; j < elementsPerLong; ++j) {
                        var blockIndex = (int)(l & maxValue);

                        processBlockWithPaletteInfo(idsWithPolys, i, blockIndex, yOffset, wizardMap);

                        l >>= elementBits;
                        ++i;
                        if (i >= size) {
                            break data;
                        }
                    }
                }
            } else {
                for (int i = 0; i < data.getSize(); i++) {
                    var blockIndex = data.get(i);
                    processBlockWithPaletteInfo(idsWithPolys, i, blockIndex, yOffset, wizardMap);
                }
            }
        } else {
            // It's not worth iterating the palette, instead iterate the blocks in the data
            if (data instanceof PackedIntegerArray) {
                // Fast way of iterating the packed data with an index
                int i = 0;

                var elementBits = data.getElementBits();
                var elementsPerLong = (char)(64 / elementBits);
                var maxValue = (1L << elementBits) - 1L;
                var size = data.getSize();

                data:
                for (long l : data.getData()) {
                    for (int j = 0; j < elementsPerLong; ++j) {
                        var blockIndex = (int)(l & maxValue);

                        processBlock(palette, polyMap, i, blockIndex, yOffset, wizardMap);

                        l >>= elementBits;
                        ++i;
                        if (i >= size) {
                            break data;
                        }
                    }
                }
            } else {
                for (int i = 0; i < data.getSize(); i++) {
                    var blockIndex = data.get(i);
                    processBlock(palette, polyMap, i, blockIndex, yOffset, wizardMap);
                }
            }
        }
    }

    private void processBlockWithPaletteInfo(BlockPoly[] polysPerPaletteId, int index, int paletteId, int yOffset, Map<BlockPos,Wizard> wizardMap) {
        var poly = polysPerPaletteId[paletteId];
        if (poly != null) {
            BlockPos pos = Util.fromPalettedContainerIndex(index).add(this.pos.x * 16, yOffset, this.pos.z * 16);
            var wiz = poly.createWizard(new PlacedWizardInfo(pos, (ServerWorld)this.world));
            ((WizardTickerDuck)this.world).polymc$addTicker(wiz);
            wizardMap.put(pos, wiz);
        }
    }

    private void processBlock(Palette<BlockState> palette, PolyMap polyMap, int index, int paletteId, int yOffset, Map<BlockPos,Wizard> wizardMap) {
        var poly = polyMap.getBlockPoly(palette.get(paletteId).getBlock());
        if (poly != null) {
            BlockPos pos = Util.fromPalettedContainerIndex(index).add(this.pos.x * 16, yOffset, this.pos.z * 16);
            var wiz = poly.createWizard(new PlacedWizardInfo(pos, (ServerWorld)this.world));
            ((WizardTickerDuck)this.world).polymc$addTicker(wiz);
            wizardMap.put(pos, wiz);
        }
    }

    @Override
    public void addPlayer(ServerPlayerEntity playerEntity) {
        PolyMap map = PolyMapProvider.getPolyMap(playerEntity);
        this.wizards.get(map).values().forEach((wizard) -> wizard.addPlayer(playerEntity));
        players.add(playerEntity);
    }

    @Override
    public void removePlayer(ServerPlayerEntity playerEntity) {
        PolyMap map = PolyMapProvider.getPolyMap(playerEntity);
        this.wizards.get(map).values().forEach((wizard) -> wizard.removePlayer(playerEntity));
        players.remove(playerEntity);
    }

    @Override
    public void removeAllPlayers() {
        this.wizards.values().forEach((wizardMap) -> wizardMap.values().forEach(wizard -> {
            wizard.removeAllPlayers();
            ((WizardTickerDuck)this.world).polymc$removeTicker(wizard);
        }));
        this.wizards.clear();
        this.players.clear();
    }

    @Inject(method = "setBlockState", at = @At("TAIL"))
    private void onSet(BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> cir) {
        wizards.forEach((polyMap, wizardMap) -> {
            Wizard oldWiz = wizardMap.remove(pos);
            if (oldWiz != null) {
                oldWiz.onRemove();
                ((WizardTickerDuck)this.world).polymc$removeTicker(oldWiz);
            }

            BlockPoly poly = polyMap.getBlockPoly(state.getBlock());
            if (poly != null && poly.hasWizard()) {
                BlockPos ipos = pos.toImmutable();
                Wizard wiz = poly.createWizard(new PlacedWizardInfo(ipos, (ServerWorld)this.world));
                wizardMap.put(ipos, wiz);
                for (ServerPlayerEntity player : players) {
                    wiz.addPlayer(player);
                }
                ((WizardTickerDuck)this.world).polymc$addTicker(wiz);
            }
        });
    }

    @Override
    public PolyMapMap<Wizard> getWizards(BlockPos pos) {
        PolyMapMap<Wizard> ret = new PolyMapMap<>(null);
        this.wizards.forEach((polyMap, wizardMap) -> {
            Wizard wizard = wizardMap.get(pos);
            if (wizard != null) ret.put(polyMap, wizard);
        });
        return ret;
    }

    @Override
    public PolyMapMap<Wizard> removeWizards(BlockPos pos, boolean move) {
        PolyMapMap<Wizard> ret = new PolyMapMap<>(null);

        this.wizards.forEach((polyMap, wizardMap) -> {
            Wizard wizard = wizardMap.remove(pos);
            if (wizard != null) {
                if (!move) wizard.onRemove();
                ((WizardTickerDuck)this.world).polymc$removeTicker(wizard);
                ret.put(polyMap, wizard);
            }
        });
        return ret;
    }
}
