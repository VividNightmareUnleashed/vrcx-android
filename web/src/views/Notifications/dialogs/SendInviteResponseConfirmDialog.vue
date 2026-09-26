<template>
    <Dialog
        :open="sendInviteResponseConfirmDialog.visible"
        @update:open="(open) => (open ? null : cancelInviteResponseConfirm())">
        <!-- Phones: a small confirmation stays a centred card (docs/DESIGN.md §3.2); inert on PC. -->
        <DialogContent data-mobile="card">
            <DialogHeader>
                <DialogTitle>{{ t('dialog.invite_response_message.header') }}</DialogTitle>
            </DialogHeader>
            <div class="text-xs">
                <span>{{ t('dialog.invite_response_message.confirmation') }}</span>
            </div>

            <DialogFooter>
                <component :is="FooterButton" variant="secondary" class="mr-2" @click="cancelInviteResponseConfirm">{{
                    t('dialog.invite_response_message.cancel')
                }}</component>
                <component :is="FooterButton" @click="sendInviteResponseConfirm">{{
                    t('dialog.invite_response_message.confirm')
                }}</component>
            </DialogFooter>
        </DialogContent>
    </Dialog>
</template>

<script setup>
    import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
    import { Button } from '@/components/ui/button';
    import { isAndroid } from '../../../shared/utils/platform';
    import { storeToRefs } from 'pinia';
    import { toast } from 'vue-sonner';
    import { useI18n } from 'vue-i18n';

    import { useGalleryStore, useNotificationStore } from '../../../stores';
    import { notificationRequest } from '../../../api';

    const { t } = useI18n();

    // Upstream never imports Button here, so PC renders plain <button> elements; Android uses the real Button (VRCX
    // styling and a proper touch target) and PC keeps its plain buttons.
    const FooterButton = isAndroid ? Button : 'button';

    const galleryStore = useGalleryStore();
    const { uploadImage } = storeToRefs(galleryStore);

    const props = defineProps({
        sendInviteResponseDialog: {
            type: Object,
            default: () => ({})
        },
        sendInviteResponseConfirmDialog: {
            type: Object,
            required: true
        }
    });

    const emit = defineEmits(['closeResponseConfirmDialog', 'closeInviteDialog']);

    function cancelInviteResponseConfirm() {
        emit('closeResponseConfirmDialog');
    }

    function sendInviteResponseConfirm() {
        const D = props.sendInviteResponseDialog;
        const params = {
            responseSlot: D.messageSlot.slot,
            rsvp: true
        };
        if (uploadImage.value) {
            notificationRequest
                .sendInviteResponsePhoto(params, D.invite.id)
                .catch((err) => {
                    console.error('Invite response photo failed', err);
                    toast.error(t('message.error'));
                })
                .then((args) => {
                    notificationRequest
                        .hideNotification({
                            notificationId: D.invite.id
                        })
                        .then(() => {
                            useNotificationStore().handleNotificationHide(D.invite.id);
                        });
                    toast.success(t('message.invite.response_photo_sent'));
                    return args;
                })
                .finally(() => {
                    emit('closeInviteDialog');
                });
        } else {
            notificationRequest
                .sendInviteResponse(params, D.invite.id)
                .catch((err) => {
                    console.error('Invite response failed', err);
                    toast.error(t('message.error'));
                })
                .then((args) => {
                    notificationRequest
                        .hideNotification({
                            notificationId: D.invite.id
                        })
                        .then(() => {
                            useNotificationStore().handleNotificationHide(D.invite.id);
                        });
                    toast.success(t('message.invite.response_sent'));
                    return args;
                })
                .finally(() => {
                    emit('closeInviteDialog');
                });
        }
        cancelInviteResponseConfirm();
    }
</script>
