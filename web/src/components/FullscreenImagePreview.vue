<template>
    <Dialog v-model:open="open">
        <DialogPortal :to="portalTo">
            <RekaDialogOverlay
                :class="cn('fixed inset-0 bg-background/80', !disableGpuAcceleration && 'backdrop-blur-sm')" />

            <RekaDialogContent
                class="fixed inset-0 p-6 sm:p-10 border-0 bg-transparent shadow-none outline-none compact:p-0"
                @click="closeDialog"
                @open-auto-focus.prevent
                @close-auto-focus.prevent>
                <div
                    ref="viewerEl"
                    class="relative h-full w-full overflow-hidden select-none"
                    @pointerdown.capture="onViewerPointerDownCapture"
                    @click.capture="onViewerClickCapture">
                    <!-- toolbar -->
                    <div
                        @click.stop
                        class="vrcx-viewer-toolbar absolute right-3 top-3 z-10 flex items-center gap-2 rounded-md bg-background/70 backdrop-blur px-2 py-1 border compact:gap-0.5 compact:px-1 compact:top-[calc(var(--safe-top,0px)+8px)] compact:right-[calc(var(--safe-right,0px)+8px)]">
                        <Button
                            variant="ghost"
                            size="icon"
                            class="h-8 w-8"
                            :disabled="!imageUrl"
                            @click="copyImageToClipboard(imageUrl)"
                            :ariaLabel="t('common.actions.copy')">
                            <Copy class="h-4 w-4" />
                        </Button>

                        <Button
                            variant="ghost"
                            size="icon"
                            class="h-8 w-8"
                            :disabled="!imageUrl"
                            @click="downloadAndSaveImage(imageUrl, fullscreenImageDialog.fileName)"
                            :ariaLabel="t('dialog.vrcx_updater.download')">
                            <Download class="h-4 w-4" />
                        </Button>

                        <div class="mx-1 h-5 w-px bg-border compact:hidden" />

                        <Button
                            variant="ghost"
                            size="icon"
                            class="h-8 w-8"
                            @click="zoomOutCenter"
                            :ariaLabel="t('dialog.image_crop.zoom_out')">
                            <ZoomOut class="h-4 w-4" />
                        </Button>
                        <Button
                            variant="ghost"
                            size="icon"
                            class="h-8 w-8"
                            @click="zoomInCenter"
                            :ariaLabel="t('dialog.image_crop.zoom_in')">
                            <ZoomIn class="h-4 w-4" />
                        </Button>

                        <Button
                            variant="ghost"
                            size="icon"
                            class="h-8 w-8"
                            @click="rotateCW"
                            :ariaLabel="t('dialog.image_crop.rotate_right')">
                            <RotateCw class="h-4 w-4" />
                        </Button>
                        <Button
                            variant="ghost"
                            size="icon"
                            class="h-8 w-8"
                            @click="rotateCCW"
                            :ariaLabel="t('dialog.image_crop.rotate_left')">
                            <RotateCcw class="h-4 w-4" />
                        </Button>

                        <Button
                            variant="ghost"
                            size="icon"
                            class="h-8 w-8"
                            @click="resetTransform"
                            :ariaLabel="t('dialog.image_crop.reset')">
                            <RefreshCcw class="h-4 w-4" />
                        </Button>

                        <div class="mx-1 h-5 w-px bg-border compact:hidden" />

                        <Button
                            variant="ghost"
                            size="icon"
                            class="h-8 w-8"
                            @click="closeDialog"
                            :ariaLabel="t('dialog.shared_feed_filters.close')">
                            <X class="h-4 w-4" />
                        </Button>
                    </div>

                    <!-- Pointer handling lives on the stage so a pinch can start beside the image; touch-action none
                         keeps the browser from turning touches into scrolls (docs/DESIGN.md §3.3). -->
                    <div
                        data-slot="viewer-stage"
                        class="h-full w-full flex items-center justify-center touch-none"
                        @wheel="onWheel"
                        @pointerdown="onPointerDown"
                        @pointermove="onPointerMove"
                        @pointerup="onPointerUp"
                        @pointercancel="onPointerUp">
                        <img
                            ref="imageEl"
                            @click.stop
                            v-if="imageUrl"
                            :src="imageUrl"
                            class="max-h-full max-w-full x-viewer-img touch-none"
                            :style="transformStyle"
                            draggable="false" />
                    </div>
                </div>
            </RekaDialogContent>
        </DialogPortal>
    </Dialog>
</template>

<script setup>
    import { Copy, Download, RefreshCcw, RotateCcw, RotateCw, X, ZoomIn, ZoomOut } from 'lucide-vue-next';
    import { useEventListener } from '@vueuse/core';
    import { computed, onBeforeUnmount, ref, watch } from 'vue';
    import { DialogContent as RekaDialogContent, DialogOverlay as RekaDialogOverlay, DialogPortal } from 'reka-ui';
    import { Button } from '@/components/ui/button';
    import { Dialog } from '@/components/ui/dialog';
    import { acquireModalPortalLayer } from '@/lib/modalPortalLayers';
    import { cn } from '@/lib/utils';
    import { storeToRefs } from 'pinia';
    import { toast } from 'vue-sonner';
    import { useGeneralSettingsStore } from '@/stores/settings/general';
    import { useI18n } from 'vue-i18n';

    import { extractFileId } from '../shared/utils';
    import { useGalleryStore } from '../stores';
    import {
        DOUBLE_TAP_MAX_DELAY_MS,
        TAP_MAX_MOVE_PX,
        clamp,
        distance,
        doubleTapState,
        isDoubleTap,
        midpoint,
        pinchState,
        zoomAtPoint
    } from './ui/zoom-pan/zoomPan';

    const galleryStore = useGalleryStore();
    const { fullscreenImageDialog } = storeToRefs(galleryStore);
    const { disableGpuAcceleration } = storeToRefs(useGeneralSettingsStore());
    const { t } = useI18n();

    const viewerEl = ref(null);
    const imageEl = ref(null);
    const portalLayer = acquireModalPortalLayer();
    const portalTo = portalLayer.element;

    const scale = ref(1);
    const rotate = ref(0); // deg
    const tx = ref(0);
    const ty = ref(0);

    const isDragging = ref(false);
    const dragStartX = ref(0);
    const dragStartY = ref(0);
    const startTx = ref(0);
    const startTy = ref(0);

    const imageUrl = computed(() => fullscreenImageDialog.value.imageUrl || '');

    const open = computed({
        get: () => fullscreenImageDialog.value.visible,
        set: (v) => {
            fullscreenImageDialog.value.visible = v;
        }
    });

    function resetTransform() {
        scale.value = 1;
        rotate.value = 0;
        tx.value = 0;
        ty.value = 0;
    }

    function closeDialog() {
        open.value = false;
    }

    function zoomAtCenter(factor) {
        scale.value = clamp(scale.value * factor, 0.1, 10);
    }

    function zoomInCenter() {
        zoomAtCenter(1.2);
    }
    function zoomOutCenter() {
        zoomAtCenter(1 / 1.2);
    }

    function rotateCW() {
        rotate.value = (rotate.value + 90) % 360;
    }
    function rotateCCW() {
        rotate.value = (rotate.value - 90 + 360) % 360;
    }

    function getViewState() {
        return { scale: scale.value, rotate: rotate.value, tx: tx.value, ty: ty.value };
    }

    function applyViewState(state) {
        scale.value = state.scale;
        rotate.value = state.rotate;
        tx.value = state.tx;
        ty.value = state.ty;
    }

    /**
     * Client coordinates relative to the viewer centre.
     *
     * @param {{ x: number; y: number }} point
     * @param {DOMRect} rect
     */
    function toViewerPoint(point, rect) {
        return { x: point.x - rect.left - rect.width / 2, y: point.y - rect.top - rect.height / 2 };
    }

    function zoomAtPointer(e, factor) {
        const el = viewerEl.value;
        if (!el) return;
        const rect = el.getBoundingClientRect();
        applyViewState(zoomAtPoint(getViewState(), toViewerPoint({ x: e.clientX, y: e.clientY }, rect), factor));
    }

    function onWheel(e) {
        e.preventDefault();
        const factor = e.deltaY < 0 ? 1.1 : 1 / 1.1;
        zoomAtPointer(e, factor);
    }

    // Touch gestures: one finger pans (from the image), two fingers pinch, double-tap toggles zoom, and a single tap
    // beside the image closes the viewer once the double-tap window has passed.
    //
    // Mouse keeps the upstream behaviour: a drag from the image pans it, and the pointer is captured by the image, so
    // the click that ends the drag lands on the image (whose @click.stop keeps the viewer open) and not on the stage.
    // Touch pointers are captured by the stage (a pinch can start beside the image), which retargets the click after
    // a tap to the stage: every touch click is swallowed and taps are resolved from the pointer events instead.
    const touchPointers = new Map();
    let pinch = null;
    let tapStart = null;
    let lastTap = null;
    let suppressClick = false;
    let closeTimer = 0;

    function cancelPendingClose() {
        if (closeTimer) {
            clearTimeout(closeTimer);
            closeTimer = 0;
        }
    }

    function scheduleBackdropClose() {
        cancelPendingClose();
        closeTimer = setTimeout(() => {
            closeTimer = 0;
            closeDialog();
        }, DOUBLE_TAP_MAX_DELAY_MS);
    }

    function startDrag(x, y) {
        isDragging.value = true;
        dragStartX.value = x;
        dragStartY.value = y;
        startTx.value = tx.value;
        startTy.value = ty.value;
    }

    // Every gesture starts with a clean slate: a pan or pinch that ended without a click must not swallow the click
    // of the next tap (a toolbar button, for example).
    function onViewerPointerDownCapture() {
        suppressClick = false;
    }

    function onPointerDown(e) {
        if (e.pointerType === 'mouse') {
            const img = imageEl.value;
            if (e.button !== 0 || !img || e.target !== img) return;
            img.setPointerCapture?.(e.pointerId);
            startDrag(e.clientX, e.clientY);
            return;
        }

        // A second tap is on its way: the first one was not a backdrop tap.
        cancelPendingClose();
        suppressClick = true;
        touchPointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
        e.currentTarget.setPointerCapture?.(e.pointerId);

        if (touchPointers.size === 1) {
            tapStart = { x: e.clientX, y: e.clientY, time: e.timeStamp, onImage: e.target === imageEl.value };
            if (tapStart.onImage) {
                startDrag(e.clientX, e.clientY);
            }
            return;
        }

        if (touchPointers.size === 2 && viewerEl.value) {
            // The layout read happens once per pinch, not per move.
            const rect = viewerEl.value.getBoundingClientRect();
            const [a, b] = [...touchPointers.values()];
            isDragging.value = false;
            tapStart = null;
            lastTap = null;
            pinch = {
                rect,
                startDistance: distance(a, b),
                startMid: toViewerPoint(midpoint(a, b), rect),
                startState: getViewState()
            };
        }
    }

    function onPointerMove(e) {
        if (e.pointerType !== 'mouse') {
            if (!touchPointers.has(e.pointerId)) return;
            touchPointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
            if (pinch && touchPointers.size >= 2) {
                const [a, b] = [...touchPointers.values()];
                applyViewState(
                    pinchState(
                        pinch.startState,
                        pinch.startMid,
                        pinch.startDistance,
                        toViewerPoint(midpoint(a, b), pinch.rect),
                        distance(a, b)
                    )
                );
                return;
            }
            if (tapStart && distance(tapStart, { x: e.clientX, y: e.clientY }) > TAP_MAX_MOVE_PX) {
                tapStart = null;
                lastTap = null;
            }
        }
        if (!isDragging.value) return;
        const dx = e.clientX - dragStartX.value;
        const dy = e.clientY - dragStartY.value;
        tx.value = startTx.value + dx;
        ty.value = startTy.value + dy;
    }

    function onPointerUp(e) {
        if (e.pointerType === 'mouse') {
            if (!isDragging.value) return;
            isDragging.value = false;
            imageEl.value?.releasePointerCapture?.(e.pointerId);
            return;
        }

        touchPointers.delete(e.pointerId);
        e.currentTarget.releasePointerCapture?.(e.pointerId);

        if (pinch) {
            if (touchPointers.size < 2) {
                pinch = null;
                // Keep panning with the finger that stays down.
                const [rest] = [...touchPointers.values()];
                if (rest) {
                    startDrag(rest.x, rest.y);
                } else {
                    isDragging.value = false;
                }
            }
            return;
        }

        isDragging.value = false;
        if (e.type !== 'pointerup' || !tapStart) {
            tapStart = null;
            return;
        }
        const tap = { x: e.clientX, y: e.clientY, time: e.timeStamp };
        const { onImage } = tapStart;
        tapStart = null;
        if (isDoubleTap(lastTap, tap) && viewerEl.value) {
            // Double-tap anywhere on the stage: small images leave most of it to the backdrop.
            const rect = viewerEl.value.getBoundingClientRect();
            applyViewState(doubleTapState(getViewState(), toViewerPoint(tap, rect)));
            lastTap = null;
            return;
        }
        lastTap = tap;
        // A tap on the image does nothing (as a click does on PC); a tap beside it closes the viewer, unless it turns
        // out to be the first half of a double-tap.
        if (!onImage) {
            scheduleBackdropClose();
        }
    }

    // Touch clicks (taps are handled above) must not reach the backdrop's close handler.
    function onViewerClickCapture(e) {
        if (suppressClick) {
            suppressClick = false;
            e.stopPropagation();
        }
    }

    const transformStyle = computed(() => ({
        transform: `translate(${tx.value}px, ${ty.value}px) scale(${scale.value}) rotate(${rotate.value}deg)`,
        transformOrigin: 'center center'
    }));

    watch(
        () => open.value,
        (v) => {
            if (v) {
                portalLayer.bringToFront();
                resetTransform();
            } else {
                cancelPendingClose();
            }
            lastTap = null;
        }
    );

    onBeforeUnmount(() => {
        cancelPendingClose();
        portalLayer.release();
    });

    watch(
        () => imageUrl.value,
        (url) => {
            if (!url || !open.value) return;
            resetTransform();
        }
    );

    function onKeydown(e) {
        if (!open.value) return;
        if (e.key === '+' || e.key === '=') zoomInCenter();
        else if (e.key === '-' || e.key === '_') zoomOutCenter();
        else if (e.key.toLowerCase() === 'r') rotateCW();
        else if (e.key === '0') resetTransform();
    }
    useEventListener(window, 'keydown', onKeydown);

    async function copyImageToClipboard(url) {
        if (!url) return;
        const msg = toast.info(t('message.image.downloading'));
        try {
            const response = await webApiService.execute({ url, method: 'GET' });
            if (response.status !== 200 || !String(response.data).startsWith('data:image/png')) {
                throw new Error(`Error: ${response.data}`);
            }
            const blob = await (await fetch(response.data)).blob();
            await navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })]);
            toast.success(t('message.image.copied_to_clipboard'));
        } catch (error) {
            console.error('Error downloading image:', error);
            toast.error(`Failed to download image. ${url}`);
        } finally {
            toast.dismiss(msg);
        }
    }

    async function downloadAndSaveImage(url, fileName) {
        if (!url) return;
        const msg = toast.info(t('message.image.downloading'));
        try {
            const response = await webApiService.execute({ url, method: 'GET' });
            if (response.status !== 200 || !String(response.data).startsWith('data:image/png')) {
                throw new Error(`Error: ${response.data}`);
            }

            const link = document.createElement('a');
            link.href = response.data;

            const fileId = extractFileId(url);
            let name = fileName;
            if (!name && fileId) name = `${fileId}.png`;
            if (!name) name = `${url.split('/').pop()}.png`;
            if (!name) name = 'image.png';

            link.setAttribute('download', name);
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
        } catch (error) {
            console.error('Error downloading image:', error);
            toast.error(`Failed to download image. ${url}`);
        } finally {
            toast.dismiss(msg);
        }
    }
</script>

<style scoped>
    .x-viewer-img {
        will-change: transform;
        cursor: grab;
        user-select: none;
    }
    .x-viewer-img:active {
        cursor: grabbing;
    }
</style>
