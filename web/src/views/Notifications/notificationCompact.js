// Pure helpers of the notification columns, shared by the PC cells and the phone card cells (docs/DESIGN.md §3.1, §3.3).

export const isGroupId = (id) => typeof id === 'string' && id.startsWith('grp_');

/**
 * Whether the user column shows anything for this notification (mirrors its cell).
 *
 * @param {object} original
 * @returns {boolean}
 */
export const hasSenderContent = (original) =>
    Boolean(
        (original?.senderUserId && !isGroupId(original.senderUserId)) ||
        original?.link?.startsWith('user:') ||
        (original?.senderUsername && !isGroupId(original?.senderUserId))
    );

const GENERATED_INVITE_PREFIX = 'This is a generated invite to ';

/**
 * The message lines the PC message cell shows (each in a truncated span with the full text in a tooltip).
 *
 * @param {object} original
 * @returns {string[]}
 */
export const getNotificationMessageLines = (original) => {
    const lines = [];
    if (!original) return lines;
    const { message, title, details } = original;
    if (message && title) lines.push(`${title}, ${message}`);
    if (!message && title) lines.push(title);
    if (message && !title && message !== `${GENERATED_INVITE_PREFIX}${details?.worldName}`) lines.push(message);
    if (!message && details?.inviteMessage) lines.push(details.inviteMessage);
    if (!message && details?.requestMessage) lines.push(details.requestMessage);
    if (!message && details?.responseMessage) lines.push(details.responseMessage);
    return lines;
};

/**
 * Which of the optional action buttons a notification row shows (shared by the PC icons and the phone buttons).
 *
 * @param {object} original
 * @returns {{ showDecline: boolean; showDeleteLog: boolean }}
 */
export const getNotificationActionFlags = (original) => ({
    showDecline:
        original.type !== 'requestInviteResponse' &&
        original.type !== 'inviteResponse' &&
        original.type !== 'message' &&
        original.type !== 'boop' &&
        original.type !== 'groupChange' &&
        !original.type?.includes('group.') &&
        !original.type?.includes('moderation.') &&
        !original.type?.includes('instance.') &&
        !original.link?.startsWith('economy.'),
    showDeleteLog: original.type !== 'friendRequest' && original.type !== 'ignoredFriendRequest'
});
