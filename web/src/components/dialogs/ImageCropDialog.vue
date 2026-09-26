<template>
    <Dialog
        :open="open"
        @update:open="
            (v) => {
                if (!v) cancelCrop();
            }
        ">
        <DialogContent class="x-dialog sm:max-w-212.5">
            <DialogHeader>
                <DialogTitle>{{ title }}</DialogTitle>
            </DialogHeader>

            <!-- Phone landscape: the cropper takes every pixel between the header and the footer, with the tools in
                 a narrow column beside it, so the stencil handles and every tool stay on screen without scrolling.
                 Android: min-w-0 lets the dialog's grid column shrink after a rotation instead of keeping the width the
                 cropper was sized for, so the cropper can measure its new box. -->
            <div
                v-if="cropperImageSrc"
                class="mt-4 compact-landscape:mt-0 compact-landscape:flex compact-landscape:min-h-0 compact-landscape:flex-1 compact-landscape:gap-3"
                :class="{ 'min-w-0': isAndroid }">
                <Cropper
                    ref="cropperRef"
                    class="h-100 max-h-full compact:h-[min(60dvh,100vw)] compact-landscape:h-auto compact-landscape:min-w-0 compact-landscape:flex-1"
                    :src="cropperImageSrc"
                    :stencil-props="{ aspectRatio, movable: !loading, resizable: !loading }"
                    :move-image="!loading"
                    :resize-image="!loading"
                    :image-restriction="freeMode ? 'none' : 'stencil'"
                    @ready="handleCropperReady"
                    @change="onCropperChange" />

                <!-- Toolbar -->
                <div
                    class="flex items-center justify-center gap-1 mt-3 compact:flex-wrap compact:gap-y-3 compact-landscape:mt-0 compact-landscape:w-32 compact-landscape:flex-none compact-landscape:content-center compact-landscape:gap-y-1">
                    <TooltipWrapper :content="t('dialog.image_crop.rotate_left')">
                        <Button
                            size="icon-sm"
                            variant="outline"
                            class="rounded-full h-8 w-8 compact:h-10 compact:w-10"
                            :disabled="loading"
                            :ariaLabel="t('dialog.image_crop.rotate_left')"
                            @click="cropperRef?.rotate(-90)">
                            <RotateCcw class="h-4 w-4" />
                        </Button>
                    </TooltipWrapper>
                    <TooltipWrapper :content="t('dialog.image_crop.rotate_right')">
                        <Button
                            size="icon-sm"
                            variant="outline"
                            class="rounded-full h-8 w-8 compact:h-10 compact:w-10"
                            :disabled="loading"
                            :ariaLabel="t('dialog.image_crop.rotate_right')"
                            @click="cropperRef?.rotate(90)">
                            <RotateCw class="h-4 w-4" />
                        </Button>
                    </TooltipWrapper>

                    <div class="w-px h-5 bg-border mx-1 compact-landscape:hidden" />

                    <TooltipWrapper :content="t('dialog.image_crop.flip_h')">
                        <Button
                            size="icon-sm"
                            variant="outline"
                            class="rounded-full h-8 w-8 compact:h-10 compact:w-10"
                            :disabled="loading"
                            :ariaLabel="t('dialog.image_crop.flip_h')"
                            @click="cropperRef?.flip(true, false)">
                            <FlipHorizontal class="h-4 w-4" />
                        </Button>
                    </TooltipWrapper>
                    <TooltipWrapper :content="t('dialog.image_crop.flip_v')">
                        <Button
                            size="icon-sm"
                            variant="outline"
                            class="rounded-full h-8 w-8 compact:h-10 compact:w-10"
                            :disabled="loading"
                            :ariaLabel="t('dialog.image_crop.flip_v')"
                            @click="cropperRef?.flip(false, true)">
                            <FlipVertical class="h-4 w-4" />
                        </Button>
                    </TooltipWrapper>

                    <div class="w-px h-5 bg-border mx-1 compact-landscape:hidden" />

                    <!-- Zoom: part of the single PC row (display: contents); on phones its own full-width row
                         under the other tools, with a wide slider (in landscape: the zoom buttons, then the slider
                         across the tool column). -->
                    <div
                        class="contents compact:order-last compact:flex compact:w-full compact:items-center compact:gap-2 compact-landscape:mt-1 compact-landscape:flex-wrap compact-landscape:justify-between compact-landscape:gap-y-3">
                        <TooltipWrapper :content="t('dialog.image_crop.zoom_out')">
                            <Button
                                size="icon-sm"
                                variant="ghost"
                                class="rounded-full h-7 w-7 compact:h-10 compact:w-10"
                                :disabled="loading"
                                :ariaLabel="t('dialog.image_crop.zoom_out')"
                                @click="cropperRef?.zoom(0.8)">
                                <ZoomOut class="h-3.5 w-3.5 compact:h-4 compact:w-4" />
                            </Button>
                        </TooltipWrapper>
                        <Slider
                            v-model="zoomSliderValue"
                            :min="0"
                            :max="100"
                            :step="1"
                            :disabled="loading"
                            class="w-28 compact:w-auto compact:flex-1 compact-landscape:order-last compact-landscape:basis-full"
                            @value-commit="onZoomCommit" />
                        <TooltipWrapper :content="t('dialog.image_crop.zoom_in')">
                            <Button
                                size="icon-sm"
                                variant="ghost"
                                class="rounded-full h-7 w-7 compact:h-10 compact:w-10"
                                :disabled="loading"
                                :ariaLabel="t('dialog.image_crop.zoom_in')"
                                @click="cropperRef?.zoom(1.2)">
                                <ZoomIn class="h-3.5 w-3.5 compact:h-4 compact:w-4" />
                            </Button>
                        </TooltipWrapper>
                    </div>

                    <div class="w-px h-5 bg-border mx-1 compact:hidden" />

                    <TooltipWrapper
                        :content="freeMode ? t('dialog.image_crop.mode_fit') : t('dialog.image_crop.mode_free')">
                        <Button
                            data-testid="crop-mode-toggle"
                            size="icon-sm"
                            :variant="freeMode ? 'default' : 'outline'"
                            class="rounded-full h-8 w-8 compact:h-10 compact:w-10"
                            :disabled="loading"
                            :ariaLabel="freeMode ? t('dialog.image_crop.mode_fit') : t('dialog.image_crop.mode_free')"
                            @click="toggleMode">
                            <Expand v-if="freeMode" class="h-4 w-4" />
                            <Frame v-else class="h-4 w-4" />
                        </Button>
                    </TooltipWrapper>

                    <TooltipWrapper :content="t('dialog.image_crop.reset')">
                        <Button
                            size="icon-sm"
                            variant="outline"
                            class="rounded-full h-8 w-8 compact:h-10 compact:w-10"
                            :disabled="loading"
                            :ariaLabel="t('dialog.image_crop.reset')"
                            @click="handleReset">
                            <RefreshCw class="h-4 w-4" />
                        </Button>
                    </TooltipWrapper>
                </div>
            </div>

            <!-- Phones: the footer buttons get the --touch-min height of the tools above. -->
            <DialogFooter>
                <template v-if="cropperImageSrc">
                    <Button variant="secondary" size="sm" class="compact:h-10" :disabled="loading" @click="cancelCrop">
                        {{ t('dialog.change_content_image.cancel') }}
                    </Button>
                    <Button size="sm" class="compact:h-10" :disabled="loading" @click="onConfirmCrop">
                        <Spinner v-if="loading" />
                        {{ loading ? t('message.upload.loading') : t('dialog.gallery_icons.crop_image') }}
                    </Button>
                </template>
            </DialogFooter>
        </DialogContent>
    </Dialog>
</template>

<script setup>
    import {
        Expand,
        FlipHorizontal,
        FlipVertical,
        Frame,
        RefreshCw,
        RotateCcw,
        RotateCw,
        ZoomIn,
        ZoomOut
    } from 'lucide-vue-next';
    import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
    import { nextTick, ref, watch } from 'vue';
    import { Button } from '@/components/ui/button';
    import { Cropper } from 'vue-advanced-cropper';
    import { Slider } from '@/components/ui/slider';
    import { Spinner } from '@/components/ui/spinner';
    import { useI18n } from 'vue-i18n';

    import TooltipWrapper from '@/components/ui/tooltip/TooltipWrapper.vue';

    import { isAndroid } from '../../shared/utils/platform';
    import { useImageCropper } from '../../composables/useImageCropper';

    import 'vue-advanced-cropper/dist/style.css';

    const { t } = useI18n();

    const props = defineProps({
        open: {
            type: Boolean,
            required: true
        },
        title: {
            type: String,
            default: ''
        },
        aspectRatio: {
            type: Number,
            default: 4 / 3
        },
        file: {
            type: [File, null],
            default: null
        }
    });

    const emit = defineEmits(['update:open', 'confirm']);

    const loading = ref(false);
    const freeMode = ref(false);
    const fitCropperToken = ref(0);

    const zoomSliderValue = ref([50]);
    const lastZoomRatio = ref(1);

    const MIN_ZOOM_RATIO = 0.3;
    const MAX_ZOOM_RATIO = 5;
    const LOG_MIN = Math.log(MIN_ZOOM_RATIO);
    const LOG_MAX = Math.log(MAX_ZOOM_RATIO);

    const { cropperRef, cropperImageSrc, resetCropState, loadImageForCrop, getCroppedBlob } = useImageCropper();

    // Android: the phone layouts size the cropper from classes on <html> (vrcx-compact, vrcx-compact-landscape) that
    // change after the window resize event the cropper refreshes on, so after a rotation it would keep the previous
    // box. It measures itself again whenever its own box changes size (once per frame at most). Desktop: unchanged.
    if (isAndroid && typeof ResizeObserver !== 'undefined') {
        watch(
            () => cropperRef.value?.$el,
            (element, _previous, onCleanup) => {
                if (!(element instanceof Element)) {
                    return;
                }
                let initial = true;
                let frame = 0;
                const observer = new ResizeObserver(() => {
                    // The first callback reports the size the cropper was created with.
                    if (initial) {
                        initial = false;
                        return;
                    }
                    cancelAnimationFrame(frame);
                    frame = requestAnimationFrame(() => cropperRef.value?.refresh?.());
                });
                observer.observe(element);
                onCleanup(() => {
                    observer.disconnect();
                    cancelAnimationFrame(frame);
                });
            },
            { flush: 'post' }
        );
    }

    watch(
        () => props.file,
        (file) => {
            if (file) {
                loadImageForCrop(file);
            }
        }
    );

    watch(
        () => props.open,
        (open) => {
            if (!open) {
                loading.value = false;
                freeMode.value = false;
                zoomSliderValue.value = [50];
                lastZoomRatio.value = 1;
                resetCropState();
            }
        }
    );

    watch(
        () => freeMode.value,
        (isFree, wasFree) => {
            if (!isFree && wasFree) {
                scheduleFitCropper();
            }
        },
        { flush: 'post' }
    );

    /**
     * @param result
     */
    function onCropperChange(result) {
        if (!result.visibleArea || !result.image) return;
        const ratio = result.image.width / result.visibleArea.width;
        lastZoomRatio.value = ratio;
        const normalized = ((Math.log(ratio) - LOG_MIN) / (LOG_MAX - LOG_MIN)) * 100;
        zoomSliderValue.value = [Math.max(0, Math.min(100, Math.round(normalized)))];
    }

    /**
     * @param value
     */
    function onZoomCommit(value) {
        const target = value[0];
        const targetRatio = Math.exp(LOG_MIN + (target / 100) * (LOG_MAX - LOG_MIN));
        const factor = targetRatio / lastZoomRatio.value;
        cropperRef.value?.zoom(factor);
    }

    function fillCropper() {
        cropperRef.value?.setCoordinates(
            ({ imageSize }) => ({
                left: 0,
                top: 0,
                width: imageSize.width,
                height: imageSize.height
            }),
            {
                transitions: false,
                autoZoom: false
            }
        );
    }

    async function scheduleFitCropper() {
        const token = ++fitCropperToken.value;
        await nextTick();
        await nextTick();

        if (fitCropperToken.value !== token || freeMode.value) {
            return;
        }

        fillCropper();
    }

    function handleCropperReady() {
        if (!freeMode.value) {
            scheduleFitCropper();
        }
    }

    function toggleMode() {
        freeMode.value = !freeMode.value;
    }

    function handleReset() {
        freeMode.value = false;
        scheduleFitCropper();
    }

    function cancelCrop() {
        resetCropState();
        emit('update:open', false);
    }

    async function onConfirmCrop() {
        loading.value = true;
        try {
            const blob = await getCroppedBlob(props.file);
            if (!blob) {
                loading.value = false;
                return;
            }
            emit('confirm', blob);
        } catch {
            loading.value = false;
        }
    }
</script>
