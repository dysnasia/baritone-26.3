/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils;

import baritone.api.BaritoneAPI;
import baritone.api.utils.IPlayerContext;
import baritone.utils.accessor.IPlayerControllerMP;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author Brady
 * @since 8/25/2018
 */
public final class BlockBreakHelper {
    // base ticks between block breaks caused by tick logic
    private static final int BASE_BREAK_DELAY = 1;

    /**
     * How many broken blocks to remember. Whoever is interested in them drains the list every tick, so this only has
     * to survive the ticks during which nothing is listening, and it must stay bounded so that mining with nobody
     * watching cannot grow it without limit.
     */
    private static final int MAX_REMEMBERED_BREAKS = 64;

    private final IPlayerContext ctx;
    private boolean wasHitting;
    private int breakDelayTimer = 0;
    private final List<BrokenBlock> brokenBlocks = new ArrayList<>();
    private BlockPos breakingPos;
    private BlockState breakingState;

    BlockBreakHelper(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    public void stopBreakingBlock() {
        // The player controller will never be null, but the player can be
        if (ctx.player() != null && wasHitting) {
            ctx.playerController().setHittingBlock(false);
            ctx.playerController().resetBlockRemoving();
            wasHitting = false;
        }
    }

    public void tick(boolean isLeftClick) {
        if (breakDelayTimer > 0) {
            breakDelayTimer--;
            return;
        }
        HitResult trace = ctx.objectMouseOver();
        boolean isBlockTrace = trace != null && trace.getType() == HitResult.Type.BLOCK;

        if (isLeftClick && isBlockTrace) {
            // Remember what was there before we started breaking it, so we can tell afterwards whether it actually
            // went away. The client applies a successful break to its own world immediately, so a change here means
            // we broke the block, which is a far more reliable signal than watching the crosshair or polling the
            // world later: it catches blocks that break in the same tick they are first aimed at, and it never
            // mistakes someone else's mining, an explosion or a piston for our own work.
            BlockPos tracedPos = ((BlockHitResult) trace).getBlockPos().immutable();
            BlockState currentState = ctx.world().getBlockState(tracedPos);
            if (breakingPos == null || !tracedPos.equals(breakingPos) || currentState.getBlock() != breakingState.getBlock()) {
                // A block we haven't captured yet. Capture it now, because starting a break calls BlockState#attack
                // and some blocks change state in response -- redstone ore lights up -- so by the time it finally
                // breaks the world no longer holds the state the block was mined in. Keying on the block rather than
                // on an unbroken run of ticks matters: a break can be interrupted and resumed, and recapturing on
                // resume would pick up the post-attack state, which is exactly what this exists to avoid. It also
                // means a block replaced by someone else is recaptured rather than recorded as one we broke.
                breakingPos = tracedPos;
                breakingState = currentState;
            }
            BlockState stateBefore = breakingState;

            ctx.playerController().setHittingBlock(wasHitting);
            if (ctx.playerController().hasBrokenBlock()) {
                ctx.playerController().syncHeldItem();
                ctx.playerController().clickBlock(((BlockHitResult) trace).getBlockPos(), ((BlockHitResult) trace).getDirection());
                ctx.player().swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            } else {
                if (ctx.playerController().onPlayerDamageBlock(((BlockHitResult) trace).getBlockPos(), ((BlockHitResult) trace).getDirection())) {
                    ctx.player().swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                }
                if (ctx.playerController().hasBrokenBlock()) { // block broken this tick
                    // break delay timer only applies for multi-tick block breaks like vanilla
                    breakDelayTimer = BaritoneAPI.getSettings().blockBreakSpeed.value - BASE_BREAK_DELAY;
                    // must reset controller's destroy delay to prevent the client from delaying itself unnecessarily
                    ((IPlayerControllerMP) ctx.minecraft().gameMode).setDestroyDelay(0);
                }
            }
            // if true, we're breaking a block. if false, we broke the block this tick
            wasHitting = !ctx.playerController().hasBrokenBlock();
            // this value will be reset by the MC client handling mouse keys
            // since we're not spoofing the click keybind to the client, the client will stop the break if isDestroyingBlock is true
            // we store and restore this value on the next tick to determine if we're breaking a block
            ctx.playerController().setHittingBlock(false);

            // Compare the block, not the whole state, so that a block merely reacting to being hit isn't counted as
            // one we broke. wasHitting is false only once the client agrees the break finished, which rules out a
            // block that turns into a different block when struck rather than when broken.
            if (!wasHitting && ctx.world().getBlockState(tracedPos).getBlock() != stateBefore.getBlock()) {
                synchronized (brokenBlocks) {
                    if (brokenBlocks.size() >= MAX_REMEMBERED_BREAKS) {
                        brokenBlocks.remove(0);
                    }
                    brokenBlocks.add(new BrokenBlock(tracedPos, stateBefore));
                }
                breakingPos = null;
                breakingState = null;
            }
        } else {
            wasHitting = false;
        }
    }

    /**
     * @return Every block broken since the last call, oldest first, and forgets them
     */
    public List<BrokenBlock> drainBrokenBlocks() {
        // Cancelling a mine happens on the pathing executor, so the list is reachable from more than the client
        // thread and the drain has to be atomic against both that and the tick appending to it.
        synchronized (brokenBlocks) {
            if (brokenBlocks.isEmpty()) {
                return Collections.emptyList();
            }
            List<BrokenBlock> drained = new ArrayList<>(brokenBlocks);
            brokenBlocks.clear();
            return drained;
        }
    }

    /**
     * Discards any blocks broken so far, so that work done before something started listening isn't attributed to it
     */
    public void forgetBrokenBlocks() {
        synchronized (brokenBlocks) {
            brokenBlocks.clear();
        }
    }

    /**
     * A block we broke, and the state it held immediately before we broke it
     */
    public record BrokenBlock(BlockPos pos, BlockState state) {}
}
