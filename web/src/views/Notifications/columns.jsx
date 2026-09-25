import Location from '../../components/Location.vue';
import { Avatar, AvatarFallback, AvatarImage } from '../../components/ui/avatar';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Tooltip, TooltipContent, TooltipTrigger, TooltipWrapper } from '../../components/ui/tooltip';
import { ArrowUpDown, Ban, BellOff, Check, Image, Link, MessageCircle, Reply, Tag, Trash2, X } from 'lucide-vue-next';
import { storeToRefs } from 'pinia';

import { formatDateFilter } from '../../shared/utils';
import { checkCanInvite } from '../../shared/utils/invite';
import { i18n } from '../../plugins';
import {
    useGameStore,
    useInstanceStore,
    useLocationStore,
    useUiStore,
    useUserStore,
    useNotificationStore
} from '../../stores';
import { showUserDialog } from '../../coordinators/userCoordinator';
import { showWorldDialog } from '../../coordinators/worldCoordinator';
import { showGroupDialog } from '../../coordinators/groupCoordinator';

import Emoji from '../../components/Emoji.vue';
import { useCompactLayout } from '../../composables/useCompactLayout';
import {
    getNotificationActionFlags,
    getNotificationMessageLines,
    hasSenderContent,
    isGroupId
} from './notificationCompact';

const { t, te } = i18n.global;

// Phone layout (docs/DESIGN.md §3.1, §3.3): read during render, so cells follow orientation changes.
const isCompactLayout = () => useCompactLayout().isCompact.value;

export const createColumns = ({
    getNotificationCreatedAt,
    getNotificationCreatedAtTs,
    openNotificationLink,
    showFullscreenImageDialog,
    getSmallThumbnailUrl,
    acceptFriendRequestNotification,
    showSendInviteResponseDialog,
    showSendInviteRequestResponseDialog,
    acceptRequestInvite,
    sendNotificationResponse,
    hideNotification,
    hideNotificationPrompt,
    deleteNotificationLog,
    deleteNotificationLogPrompt
}) => {
    const { showSendBoopDialog } = useUserStore();

    const { shiftHeld } = storeToRefs(useUiStore());
    const { currentUser } = storeToRefs(useUserStore());
    const { lastLocation } = storeToRefs(useLocationStore());
    const { isGameRunning } = storeToRefs(useGameStore());
    const { isNotificationExpired } = useNotificationStore();

    const { cachedInstances } = useInstanceStore();

    const canInvite = () => {
        const location = lastLocation.value?.location;
        return (
            Boolean(location) &&
            isGameRunning.value &&
            checkCanInvite(location, {
                currentUserId: currentUser.value?.id,
                lastLocationStr: lastLocation.value?.location,
                cachedInstances: cachedInstances
            })
        );
    };

    const getResponseIcon = (response, notificationType) => {
        if (response?.type === 'link') {
            return Link;
        }
        switch (response?.icon) {
            case 'check':
                return Check;
            case 'cancel':
                return X;
            case 'ban':
                return Ban;
            case 'bell-slash':
                return BellOff;
            case 'reply':
                return notificationType === 'boop' ? MessageCircle : Reply;
            default:
                return Tag;
        }
    };

    const respond = (original, response) => {
        if (response.type === 'link') {
            openNotificationLink(response.data);
            return;
        }
        if (response.icon === 'reply' && original.type === 'boop') {
            showSendBoopDialog(original.senderUserId);
            return;
        }
        sendNotificationResponse(original.id, original.responses, response.type);
    };

    /**
     * Phone actions: the PC's icon buttons (labels only in tooltips there) as labelled buttons that wrap.
     *
     * @param {object} original
     * @returns {import('vue').VNode | null}
     */
    const renderCompactActions = (original) => {
        const { showDecline, showDeleteLog } = getNotificationActionFlags(original);
        const quick = shiftHeld.value;
        const items = [];
        if (original.senderUserId !== currentUser.value?.id && !isNotificationExpired(original)) {
            if (original.type === 'friendRequest') {
                items.push({
                    key: 'accept',
                    icon: Check,
                    label: t('view.notification.actions.accept'),
                    onClick: () => acceptFriendRequestNotification(original)
                });
            }
            if (original.type === 'invite') {
                items.push({
                    key: 'decline-with-message',
                    icon: MessageCircle,
                    label: t('view.notification.actions.decline_with_message'),
                    onClick: () => showSendInviteResponseDialog(original)
                });
            }
            if (original.type === 'requestInvite') {
                if (canInvite()) {
                    items.push({
                        key: 'invite',
                        icon: Check,
                        label: t('view.notification.actions.invite'),
                        onClick: () => acceptRequestInvite(original)
                    });
                }
                items.push({
                    key: 'decline-with-message',
                    icon: MessageCircle,
                    label: t('view.notification.actions.decline_with_message'),
                    onClick: () => showSendInviteRequestResponseDialog(original)
                });
            }
            if (Array.isArray(original.responses)) {
                for (const response of original.responses) {
                    items.push({
                        key: `response:${response.text}:${response.type}`,
                        icon: getResponseIcon(response, original.type),
                        label: response.text,
                        onClick: () => respond(original, response)
                    });
                }
            }
            if (showDecline) {
                items.push({
                    key: 'decline',
                    icon: X,
                    label: t('view.notification.actions.decline'),
                    destructive: quick,
                    onClick: () => (quick ? hideNotification(original) : hideNotificationPrompt(original))
                });
            }
            if (original.type === 'group.queueReady') {
                items.push({
                    key: 'queue-delete-log',
                    icon: quick ? X : Trash2,
                    label: t('view.notification.actions.delete_log'),
                    destructive: quick,
                    onClick: () => (quick ? deleteNotificationLog(original) : deleteNotificationLogPrompt(original))
                });
            }
        }
        if (showDeleteLog && original.type !== 'group.queueReady') {
            items.push({
                key: 'delete-log',
                icon: quick ? X : Trash2,
                label: t('view.notification.actions.delete_log'),
                destructive: quick,
                onClick: () => (quick ? deleteNotificationLog(original) : deleteNotificationLogPrompt(original))
            });
        }
        if (!items.length) {
            return null;
        }
        return (
            <div class="flex flex-wrap items-center gap-1.5 pt-0.5" data-testid="notification-compact-actions">
                {items.map((item) => {
                    const Icon = item.icon;
                    return (
                        <Button
                            key={item.key}
                            variant="outline"
                            size="sm"
                            class={[
                                'h-8 max-w-full gap-1.5 px-2.5 text-xs',
                                item.destructive ? 'text-destructive' : ''
                            ]}
                            onClick={item.onClick}
                        >
                            <Icon class="size-3.5 shrink-0" />
                            <span class="truncate">{item.label}</span>
                        </Button>
                    );
                })}
            </div>
        );
    };

    /**
     * Phone message: the full text on up to three lines, plus what PC only shows in the type badge's tooltip (the
     * instance of queue and closed-instance notifications, the link text of linked ones).
     *
     * @param {object} original
     * @returns {import('vue').VNode | null}
     */
    const renderCompactMessage = (original) => {
        const parts = [];
        if ((original.type === 'group.queueReady' || original.type === 'instance.closed') && original.location) {
            parts.push(
                <Location
                    key="type-location"
                    location={original.location}
                    hint={original.worldName}
                    grouphint={original.groupName}
                    link={true}
                />
            );
        } else if (
            original.link &&
            original.linkText &&
            // A group link's text is the group name, which is already the card title.
            (hasSenderContent(original) || !original.link.startsWith('group:'))
        ) {
            parts.push(
                <span key="link-text" class="block truncate text-xs text-muted-foreground">
                    {original.linkText}
                </span>
            );
        }
        if (original.type === 'invite' && original.details) {
            parts.push(
                <Location
                    key="invite-location"
                    location={original.details.worldId}
                    hint={original.details.worldName}
                    grouphint={original.details.groupName}
                    link
                />
            );
        }
        getNotificationMessageLines(original).forEach((line, index) => {
            parts.push(
                <TooltipWrapper key={`line-${index}`} content={line} delayDuration={500}>
                    <span class="line-clamp-3 whitespace-pre-line break-words">{line}</span>
                </TooltipWrapper>
            );
        });
        return parts.length ? <div class="flex w-full min-w-0 flex-col gap-0.5">{parts}</div> : null;
    };

    /**
     * Group column content (also the card title of group notifications on phones).
     *
     * @param {object} original
     * @returns {import('vue').VNode | null}
     */
    const renderGroupCell = (original) => {
        const label =
            original.senderUsername ||
            original.groupName ||
            original.data?.groupName ||
            original.details?.groupName ||
            original.linkText;

        if (original.senderUserId && (original.type === 'groupChange' || isGroupId(original.senderUserId))) {
            return (
                <span class="table-user-text block w-full min-w-0 truncate">
                    <span
                        class="cursor-pointer block w-full min-w-0 truncate"
                        onClick={() => showGroupDialog(original.senderUserId)}
                    >
                        {label}
                    </span>
                </span>
            );
        }

        if (original.type === 'groupChange' && original.senderUsername) {
            return <span class="table-user-text block w-full min-w-0 truncate">{original.senderUsername}</span>;
        }

        if (original.link?.startsWith('group:')) {
            return (
                <span class="table-user-text block w-full min-w-0 truncate">
                    <span
                        class="cursor-pointer block w-full min-w-0 truncate"
                        onClick={() => openNotificationLink(original.link)}
                    >
                        {original.data?.groupName || label}
                    </span>
                </span>
            );
        }

        if (original.link?.startsWith('event:')) {
            return (
                <span class="table-user-text block w-full min-w-0 truncate">
                    <span
                        class="cursor-pointer block w-full min-w-0 truncate"
                        onClick={() => openNotificationLink(original.link)}
                    >
                        {original.data?.groupName || original.groupName || label}
                    </span>
                </span>
            );
        }

        if (original.data?.groupName) {
            return <span class="table-user-text block w-full min-w-0 truncate">{original.data.groupName}</span>;
        }

        if (original.details?.groupName) {
            return <span class="table-user-text block w-full min-w-0 truncate">{original.details.groupName}</span>;
        }

        if (original.groupName) {
            return <span class="table-user-text block w-full min-w-0 truncate">{original.groupName}</span>;
        }

        return null;
    };

    return [
        {
            id: 'spacer',
            header: () => null,
            enableSorting: false,
            size: 20,
            minSize: 0,
            maxSize: 20,
            meta: { mobile: { slot: 'hidden' } },
            cell: () => null
        },
        {
            accessorFn: (row) => getNotificationCreatedAtTs(row),
            id: 'created_at',
            size: 120,
            meta: { label: () => t('table.notification.date'), mobile: { slot: 'trailing' } },
            header: ({ column }) => (
                <Button
                    variant="ghost"
                    class="pl-0!"
                    onClick={() => column.toggleSorting(column.getIsSorted() === 'asc')}
                >
                    {t('table.notification.date')}
                    <ArrowUpDown class="ml-1 h-4 w-4" />
                </Button>
            ),
            sortingFn: (rowA, rowB, columnId) => {
                const a = rowA.getValue(columnId) ?? 0;
                const b = rowB.getValue(columnId) ?? 0;
                if (a !== b) {
                    return a - b;
                }

                const aId = typeof rowA.original?.id === 'string' ? rowA.original.id : '';
                const bId = typeof rowB.original?.id === 'string' ? rowB.original.id : '';
                return aId.localeCompare(bId);
            },
            cell: ({ row }) => {
                const createdAt = getNotificationCreatedAt(row.original);
                const shortText = formatDateFilter(createdAt, 'short');
                const longText = formatDateFilter(createdAt, 'long');

                // TooltipWrapper: on phones a tap or long-press shows the full date (docs/DESIGN.md §3.3).
                return (
                    <TooltipWrapper side="right" content={longText}>
                        <span>{shortText}</span>
                    </TooltipWrapper>
                );
            }
        },
        {
            accessorKey: 'type',
            size: 180,
            header: () => t('table.notification.type'),
            meta: { label: () => t('table.notification.type'), mobile: { slot: 'badge' } },
            cell: ({ row }) => {
                const original = row.original;
                const typeKey = `view.notification.filters.${original.type}`;
                const label = te(typeKey) ? t(typeKey) : original.type;

                if (original.type === 'invite') {
                    return (
                        <Badge variant="outline" class="text-muted-foreground">
                            {label}
                        </Badge>
                    );
                }

                if (original.type === 'group.queueReady' || original.type === 'instance.closed') {
                    return (
                        <Badge variant="outline" class="text-muted-foreground">
                            <Tooltip>
                                <TooltipTrigger asChild>
                                    <span class="cursor-pointer" onClick={() => showWorldDialog(original.location)}>
                                        {label}
                                    </span>
                                </TooltipTrigger>
                                <TooltipContent side="top">
                                    {original.location ? (
                                        <Location
                                            location={original.location}
                                            hint={original.worldName}
                                            grouphint={original.groupName}
                                            link={true}
                                        />
                                    ) : null}
                                </TooltipContent>
                            </Tooltip>
                        </Badge>
                    );
                }

                if (original.link) {
                    return (
                        <Badge variant="outline" class="text-muted-foreground">
                            <Tooltip>
                                <TooltipTrigger asChild>
                                    <span class="cursor-pointer" onClick={() => openNotificationLink(original.link)}>
                                        {label}
                                    </span>
                                </TooltipTrigger>
                                <TooltipContent side="top">
                                    <span>{original.linkText}</span>
                                </TooltipContent>
                            </Tooltip>
                        </Badge>
                    );
                }

                return (
                    <Badge variant="outline" class="text-muted-foreground">
                        {label}
                    </Badge>
                );
            }
        },
        {
            accessorKey: 'senderUsername',
            meta: {
                class: 'overflow-hidden',
                label: () => t('table.notification.user'),
                mobile: { slot: 'title' }
            },
            size: 150,
            header: () => t('table.notification.user'),
            cell: ({ row }) => {
                const original = row.original;
                // Phones: group notifications have no user, so the card title shows the group instead.
                if (isCompactLayout() && !hasSenderContent(original)) {
                    return renderGroupCell(original);
                }
                if (original.senderUserId && !isGroupId(original.senderUserId)) {
                    return (
                        <span class="table-user-text block w-full min-w-0 truncate">
                            <span
                                class="cursor-pointer block w-full min-w-0 truncate"
                                onClick={() => showUserDialog(original.senderUserId)}
                            >
                                {original.senderUsername}
                            </span>
                        </span>
                    );
                }

                if (original.link?.startsWith('user:')) {
                    return (
                        <span class="table-user-text block w-full min-w-0 truncate">
                            <span
                                class="cursor-pointer block w-full min-w-0 truncate"
                                onClick={() => openNotificationLink(original.link)}
                            >
                                {original.linkText || original.senderUsername}
                            </span>
                        </span>
                    );
                }

                if (original.senderUsername && !isGroupId(original.senderUserId)) {
                    return <span class="table-user-text block w-full min-w-0 truncate">{original.senderUsername}</span>;
                }

                return null;
            }
        },
        {
            accessorKey: 'groupName',
            meta: {
                class: 'overflow-hidden',
                label: () => t('table.notification.group'),
                mobile: { slot: 'body', class: 'text-xs text-muted-foreground' }
            },
            size: 150,
            header: () => t('table.notification.group'),
            cell: ({ row }) => {
                const original = row.original;
                // Phones: the card title already shows the group when there is no user (see the user column).
                if (isCompactLayout() && !hasSenderContent(original)) {
                    return null;
                }
                return renderGroupCell(original);
            }
        },
        {
            accessorKey: 'photo',
            size: 80,
            header: () => t('table.notification.photo'),
            meta: { label: () => t('table.notification.photo'), mobile: { slot: 'leading' } },
            cell: ({ row }) => {
                const original = row.original;
                if (original.type === 'boop') {
                    const imageUrl = original.details?.imageUrl || original.imageUrl;
                    if (!imageUrl || imageUrl.startsWith('default_')) {
                        return null;
                    }
                    return (
                        <Emoji
                            class="cursor-pointer h-7.5 w-7.5 rounded object-cover"
                            onClick={() => showFullscreenImageDialog(imageUrl)}
                            imageUrl={imageUrl}
                            size={30}
                        />
                    );
                }

                if (original.details?.imageUrl) {
                    const detailsUrl = getSmallThumbnailUrl(original.details.imageUrl);
                    return (
                        <Avatar
                            class="cursor-pointer size-7.5 rounded"
                            onClick={() => showFullscreenImageDialog(original.details.imageUrl)}
                        >
                            <AvatarImage src={detailsUrl} class="object-cover" />
                            <AvatarFallback class="rounded">
                                <Image class="size-4 text-muted-foreground" />
                            </AvatarFallback>
                        </Avatar>
                    );
                }

                if (original.imageUrl) {
                    const imgUrl = getSmallThumbnailUrl(original.imageUrl);
                    return (
                        <Avatar
                            class="cursor-pointer size-7.5 rounded"
                            onClick={() => showFullscreenImageDialog(original.imageUrl)}
                        >
                            <AvatarImage src={imgUrl} class="object-cover" />
                            <AvatarFallback class="rounded">
                                <Image class="size-4 text-muted-foreground" />
                            </AvatarFallback>
                        </Avatar>
                    );
                }

                return null;
            }
        },
        {
            id: 'message',
            header: () => t('table.notification.message'),
            enableSorting: false,
            meta: {
                class: 'min-w-0 overflow-hidden',
                stretch: true,
                label: () => t('table.notification.message'),
                // The compact renderer clamps its own lines (up to three).
                mobile: { slot: 'body', class: 'line-clamp-none!' }
            },
            minSize: 100,
            cell: ({ row }) => {
                const original = row.original;
                if (isCompactLayout()) {
                    return renderCompactMessage(original);
                }
                return (
                    <div class="w-full min-w-0">
                        {original.type === 'invite' && original.details ? (
                            <div class="w-full min-w-0">
                                <Location
                                    location={original.details.worldId}
                                    hint={original.details.worldName}
                                    grouphint={original.details.groupName}
                                    link
                                />
                            </div>
                        ) : null}
                        {original.message && original.title ? (
                            <TooltipWrapper content={`${original.title}, ${original.message}`} delayDuration={500}>
                                <span class="block w-full min-w-0 truncate">
                                    {`${original.title}, ${original.message}`}
                                </span>
                            </TooltipWrapper>
                        ) : null}
                        {!original.message && original.title ? (
                            <TooltipWrapper content={original.title} delayDuration={500}>
                                <span class="block w-full min-w-0 truncate">{original.title}</span>
                            </TooltipWrapper>
                        ) : null}
                        {original.message &&
                        !original.title &&
                        original.message !== `This is a generated invite to ${original.details?.worldName}` ? (
                            <TooltipWrapper content={original.message} delayDuration={500}>
                                <span class="block w-full min-w-0 truncate">{original.message}</span>
                            </TooltipWrapper>
                        ) : null}
                        {!original.message && original.details?.inviteMessage ? (
                            <TooltipWrapper content={original.details.inviteMessage} delayDuration={500}>
                                <span class="block w-full min-w-0 truncate">{original.details.inviteMessage}</span>
                            </TooltipWrapper>
                        ) : null}
                        {!original.message && original.details?.requestMessage ? (
                            <TooltipWrapper content={original.details.requestMessage} delayDuration={500}>
                                <span class="block w-full min-w-0 truncate">{original.details.requestMessage}</span>
                            </TooltipWrapper>
                        ) : null}
                        {!original.message && original.details?.responseMessage ? (
                            <TooltipWrapper content={original.details.responseMessage} delayDuration={500}>
                                <span class="block w-full min-w-0 truncate">{original.details.responseMessage}</span>
                            </TooltipWrapper>
                        ) : null}
                    </div>
                );
            }
        },
        {
            id: 'action',
            meta: {
                class: 'text-right',
                label: () => t('table.notification.action'),
                // Phones: labelled buttons that wrap under the message (docs/DESIGN.md §3.3).
                mobile: { slot: 'body', class: 'line-clamp-none!' }
            },
            size: 120,
            minSize: 120,
            maxSize: 120,
            header: () => t('table.notification.action'),
            enableSorting: false,
            cell: ({ row }) => {
                const original = row.original;
                if (isCompactLayout()) {
                    return renderCompactActions(original);
                }
                const hasResponses = Array.isArray(original.responses);
                const { showDecline, showDeleteLog } = getNotificationActionFlags(original);

                return (
                    <div class="flex items-center justify-end gap-2">
                        {original.senderUserId !== currentUser.value?.id && !isNotificationExpired(original) ? (
                            <span class="inline-flex items-center gap-2">
                                {original.type === 'friendRequest' ? (
                                    <Tooltip>
                                        <TooltipTrigger asChild>
                                            <button
                                                type="button"
                                                class="inline-flex h-6 ml-1 items-center justify-center text-muted-foreground hover:text-foreground cursor-pointer"
                                                aria-label={t('view.notification.actions.accept')}
                                                onClick={() => acceptFriendRequestNotification(original)}
                                            >
                                                <Check class="h-4 w-4" />
                                            </button>
                                        </TooltipTrigger>
                                        <TooltipContent side="top">
                                            <span>{t('view.notification.actions.accept')}</span>
                                        </TooltipContent>
                                    </Tooltip>
                                ) : null}

                                {original.type === 'invite' ? (
                                    <Tooltip>
                                        <TooltipTrigger asChild>
                                            <button
                                                type="button"
                                                class="inline-flex h-6 ml-1 items-center justify-center text-muted-foreground hover:text-foreground cursor-pointer"
                                                aria-label={t('view.notification.actions.decline_with_message')}
                                                onClick={() => showSendInviteResponseDialog(original)}
                                            >
                                                <MessageCircle class="h-4 w-4" />
                                            </button>
                                        </TooltipTrigger>
                                        <TooltipContent side="top">
                                            <span>{t('view.notification.actions.decline_with_message')}</span>
                                        </TooltipContent>
                                    </Tooltip>
                                ) : null}

                                {original.type === 'requestInvite' ? (
                                    <span class="inline-flex items-center">
                                        {canInvite() ? (
                                            <Tooltip>
                                                <TooltipTrigger asChild>
                                                    <button
                                                        type="button"
                                                        class="inline-flex h-6 ml-1 items-center justify-center text-muted-foreground hover:text-foreground cursor-pointer"
                                                        aria-label={t('view.notification.actions.invite')}
                                                        onClick={() => acceptRequestInvite(original)}
                                                    >
                                                        <Check class="h-4 w-4" />
                                                    </button>
                                                </TooltipTrigger>
                                                <TooltipContent side="top">
                                                    <span>{t('view.notification.actions.invite')}</span>
                                                </TooltipContent>
                                            </Tooltip>
                                        ) : null}
                                        <Tooltip>
                                            <TooltipTrigger asChild>
                                                <button
                                                    type="button"
                                                    class="inline-flex h-6 ml-1 items-center justify-center text-muted-foreground hover:text-foreground cursor-pointer"
                                                    aria-label={t('view.notification.actions.decline_with_message')}
                                                    onClick={() => showSendInviteRequestResponseDialog(original)}
                                                >
                                                    <MessageCircle class="h-4 w-4" />
                                                </button>
                                            </TooltipTrigger>
                                            <TooltipContent side="top">
                                                <span>{t('view.notification.actions.decline_with_message')}</span>
                                            </TooltipContent>
                                        </Tooltip>
                                    </span>
                                ) : null}

                                {hasResponses
                                    ? original.responses.map((response) => {
                                          const onClick = () => {
                                              if (response.type === 'link') {
                                                  openNotificationLink(response.data);
                                                  return;
                                              }
                                              if (response.icon === 'reply' && original.type === 'boop') {
                                                  showSendBoopDialog(original.senderUserId);
                                                  return;
                                              }
                                              sendNotificationResponse(original.id, original.responses, response.type);
                                          };

                                          const ResponseIcon = getResponseIcon(response, original.type);

                                          return (
                                              <Tooltip key={`${response.text}:${response.type}`}>
                                                  <TooltipTrigger asChild>
                                                      <button
                                                          type="button"
                                                          class="inline-flex h-6 ml-1 items-center justify-center text-muted-foreground hover:text-foreground cursor-pointer"
                                                          aria-label={response.text}
                                                          onClick={onClick}
                                                      >
                                                          <ResponseIcon class="h-4 w-4" />
                                                      </button>
                                                  </TooltipTrigger>
                                                  <TooltipContent side="top">
                                                      <span>{response.text}</span>
                                                  </TooltipContent>
                                              </Tooltip>
                                          );
                                      })
                                    : null}

                                {showDecline ? (
                                    <Tooltip>
                                        <TooltipTrigger asChild>
                                            <button
                                                type="button"
                                                class="inline-flex h-6 ml-1 items-center justify-center text-muted-foreground hover:text-foreground cursor-pointer"
                                                aria-label={t('view.notification.actions.decline')}
                                                onClick={() =>
                                                    shiftHeld.value
                                                        ? hideNotification(original)
                                                        : hideNotificationPrompt(original)
                                                }
                                            >
                                                <X class={shiftHeld.value ? 'h-4 w-4 text-red-600' : 'h-4 w-4'} />
                                            </button>
                                        </TooltipTrigger>
                                        <TooltipContent side="top">
                                            <span>{t('view.notification.actions.decline')}</span>
                                        </TooltipContent>
                                    </Tooltip>
                                ) : null}

                                {original.type === 'group.queueReady' ? (
                                    <Tooltip>
                                        <TooltipTrigger asChild>
                                            <button
                                                type="button"
                                                class="inline-flex h-6 ml-1 items-center justify-center text-muted-foreground hover:text-foreground cursor-pointer"
                                                aria-label={t('view.notification.actions.delete_log')}
                                                onClick={() =>
                                                    shiftHeld.value
                                                        ? deleteNotificationLog(original)
                                                        : deleteNotificationLogPrompt(original)
                                                }
                                            >
                                                {shiftHeld.value ? (
                                                    <X class="h-4 w-4 text-red-600" />
                                                ) : (
                                                    <Trash2 class="h-4 w-4" />
                                                )}
                                            </button>
                                        </TooltipTrigger>
                                    </Tooltip>
                                ) : null}
                            </span>
                        ) : null}
                        {showDeleteLog ? (
                            <Tooltip>
                                <TooltipTrigger asChild>
                                    <button
                                        type="button"
                                        class="inline-flex h-6 ml-1 items-center justify-center text-muted-foreground hover:text-foreground cursor-pointer"
                                        aria-label={t('view.notification.actions.delete_log')}
                                        onClick={() =>
                                            shiftHeld.value
                                                ? deleteNotificationLog(original)
                                                : deleteNotificationLogPrompt(original)
                                        }
                                    >
                                        {shiftHeld.value ? (
                                            <X class="h-4 w-4 text-red-600" />
                                        ) : (
                                            <Trash2 class="h-4 w-4" />
                                        )}
                                    </button>
                                </TooltipTrigger>
                                <TooltipContent side="top">
                                    <span>{t('view.notification.actions.delete_log')}</span>
                                </TooltipContent>
                            </Tooltip>
                        ) : null}
                    </div>
                );
            }
        },
        {
            id: 'trailing',
            header: () => null,
            enableSorting: false,
            enableResizing: false,
            size: 5,
            meta: { mobile: { slot: 'hidden' } },
            cell: () => null
        }
    ];
};
