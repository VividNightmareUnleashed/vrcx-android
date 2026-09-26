import { ArrowUpDown, EyeOff, User, UserMinus } from 'lucide-vue-next';
import { Avatar, AvatarFallback, AvatarImage } from '../../components/ui/avatar';

import { Button } from '../../components/ui/button';
import { Checkbox } from '../../components/ui/checkbox';
import { TooltipWrapper } from '../../components/ui/tooltip';
import { i18n } from '../../plugins';
import { useCompactLayout } from '../../composables/useCompactLayout';
import {
    formatDateFilter,
    getFaviconUrl,
    languageClass,
    openExternalLink,
    sortStatus,
    statusClass,
    timeToText
} from '../../shared/utils';

const { t } = i18n.global;

// Phone layout (docs/DESIGN.md §3.1): read during render, so cells follow orientation changes.
const isCompactLayout = () => useCompactLayout().isCompact.value;

/**
 * Phone card footer entry "Label: value", or nothing when the value is empty (friends without game log statistics
 * would otherwise show a row of bare labels).
 *
 * @param {() => string} label
 * @param {unknown} content
 * @returns {import('vue').VNode | null}
 */
const compactStat = (label, content) =>
    content === null || content === undefined || content === '' ? null : (
        <span>
            <span class="whitespace-nowrap">{label()}:</span> <span class="whitespace-nowrap">{content}</span>
        </span>
    );

/**
 * Exact date for a phone footer stat, or null when there is none (formatDateFilter returns '-').
 *
 * @param {string | undefined} value
 * @returns {string | null}
 */
const compactDate = (value) => {
    const text = formatDateFilter(value, 'long');
    return text === '-' ? null : text;
};

/** Card footer entries whose cell rendered nothing take no room (no stray gaps before the next entry). */
const FOOTER_HIDE_EMPTY = 'has-[>div:empty]:hidden';
/** Footer stats may wrap between label and value instead of being cut off (narrow cards, bulk selection). */
const FOOTER_STAT = 'has-[>div:empty]:hidden [&>div]:whitespace-normal';

const sortButton = ({ column, label, descFirst = false }) => {
    const resolvedLabel = typeof label === 'function' ? label() : label;
    return (
        <Button
            variant="ghost"
            size="sm"
            class="-ml-2 h-8 px-2"
            onClick={() => {
                const sorted = column.getIsSorted();
                if (!sorted && descFirst) {
                    column.toggleSorting(true);
                    return;
                }
                column.toggleSorting(sorted === 'asc');
            }}
        >
            {resolvedLabel}
            <ArrowUpDown class="ml-1 h-4 w-4" />
        </Button>
    );
};

const compareNumbers = (a, b) => (a ?? 0) - (b ?? 0);

const sortAlphabetically = (a, b) => {
    if (!a || !b) {
        if (!a && !b) return 0;
        return !a ? -1 : 1;
    }
    return String(a).toLowerCase().localeCompare(String(b).toLowerCase());
};

const sortLanguages = (a, b) => {
    const as = Array.isArray(a) ? a.map((i) => i.value).sort() : [];
    const bs = Array.isArray(b) ? b.map((i) => i.value).sort() : [];
    return JSON.stringify(as).localeCompare(JSON.stringify(bs));
};

const withFriendNumber = (rowA, rowB, result) => {
    if (result !== 0) {
        return result;
    }
    return compareNumbers(rowA.original?.$friendNumber, rowB.original?.$friendNumber);
};

const sortByNumber = (selector) => (rowA, rowB) =>
    withFriendNumber(rowA, rowB, compareNumbers(selector(rowA.original), selector(rowB.original)));

const sortByString = (selector) => (rowA, rowB) =>
    withFriendNumber(rowA, rowB, sortAlphabetically(selector(rowA.original), selector(rowB.original)));

const sortByStatus = (rowA, rowB) => {
    const a = rowA.original?.status ?? 'offline';
    const b = rowB.original?.status ?? 'offline';
    return withFriendNumber(rowA, rowB, sortStatus(a, b));
};

const sortByLanguages = (rowA, rowB) =>
    withFriendNumber(rowA, rowB, sortLanguages(rowA.original?.$languages, rowB.original?.$languages));

export const createColumns = ({
    randomUserColours,
    selectedFriends,
    onToggleFriendSelection,
    onConfirmDeleteFriend,
    userImage
}) => {
    const cols = [];

    cols.push(
        {
            id: 'leftSpacer',
            header: () => null,
            size: 20,
            enableSorting: false,
            enableResizing: false,
            meta: {
                thClass: 'p-0',
                tdClass: 'p-0',
                mobile: { slot: 'hidden' }
            },
            cell: () => null
        },
        {
            id: 'bulkSelect',
            header: () => null,
            size: 55,
            enableSorting: false,
            meta: {
                thClass: 'p-0',
                tdClass: 'p-0',
                mobile: { slot: 'leading', order: 0 }
            },
            cell: ({ row }) => {
                const id = row.original?.id;
                const checked = selectedFriends?.value?.has?.(id);
                if (isCompactLayout()) {
                    // Phones: a 40px target around the 16px box; a tap beside the box toggles it too.
                    return (
                        <div
                            class="-my-2 -ml-1 flex size-10 items-center justify-center"
                            data-testid="friend-bulk-select"
                            onClick={(event) => {
                                event.stopPropagation();
                                if (!event.target?.closest?.('[role="checkbox"]')) {
                                    onToggleFriendSelection?.(id);
                                }
                            }}
                        >
                            <Checkbox modelValue={checked} onUpdate:modelValue={() => onToggleFriendSelection?.(id)} />
                        </div>
                    );
                }
                return (
                    <div class="flex items-center justify-center" onClick={(event) => event.stopPropagation()}>
                        <Checkbox modelValue={checked} onUpdate:modelValue={() => onToggleFriendSelection?.(id)} />
                    </div>
                );
            }
        },
        {
            id: 'friendNumber',
            accessorFn: (row) => row?.$friendNumber,
            header: ({ column }) =>
                sortButton({
                    column,
                    label: () => t('table.friendList.no'),
                    descFirst: true
                }),
            size: 100,
            meta: { label: () => t('table.friendList.no'), mobile: { slot: 'trailing' } },
            sortingFn: sortByNumber((row) => row?.$friendNumber ?? 0),
            cell: ({ row }) =>
                isCompactLayout() ? (
                    <span>{row.original?.$friendNumber ? `#${row.original.$friendNumber}` : ''}</span>
                ) : (
                    <span>{row.original?.$friendNumber || ''}</span>
                )
        },
        {
            id: 'avatar',
            accessorFn: (row) => row?.photo,
            header: () => t('table.friendList.avatar'),
            size: 90,
            enableSorting: false,
            meta: { label: () => t('table.friendList.avatar'), mobile: { slot: 'leading', order: 1 } },
            cell: ({ row }) => {
                const src = userImage(row.original, true);
                return (
                    <div class="flex items-center">
                        <Avatar class="size-6 rounded-full compact:size-8">
                            <AvatarImage src={src} class="friends-list-avatar object-cover" loading="lazy" />
                            <AvatarFallback>
                                <User class="size-3 text-muted-foreground" />
                            </AvatarFallback>
                        </Avatar>
                    </div>
                );
            }
        },
        {
            id: 'displayName',
            accessorFn: (row) => row?.displayName,
            header: ({ column }) =>
                sortButton({
                    column,
                    label: () => t('table.friendList.displayName')
                }),
            size: 200,
            meta: { label: () => t('table.friendList.displayName'), mobile: { slot: 'title' } },
            sortingFn: sortByString((row) => row?.displayName ?? ''),
            cell: ({ row }) => {
                const style = randomUserColours?.value ? { color: row.original?.$userColour } : null;
                return (
                    <span class="name" style={style}>
                        {row.original?.displayName ?? ''}
                    </span>
                );
            }
        },
        {
            id: 'rank',
            accessorFn: (row) => row?.$trustSortNum,
            header: ({ column }) =>
                sortButton({
                    column,
                    label: () => t('table.friendList.rank')
                }),
            size: 140,
            meta: { label: () => t('table.friendList.rank'), mobile: { slot: 'badge' } },
            sortingFn: sortByNumber((row) => row?.$trustSortNum ?? 0),
            cell: ({ row }) => {
                if (randomUserColours?.value) {
                    return <span class={row.original?.$trustClass}>{row.original?.$trustLevel ?? ''}</span>;
                }
                return (
                    <span class="name" style={{ color: row.original?.$userColour }}>
                        {row.original?.$trustLevel ?? ''}
                    </span>
                );
            }
        },
        {
            id: 'status',
            accessorFn: (row) => row?.status,
            header: ({ column }) =>
                sortButton({
                    column,
                    label: () => t('table.friendList.status')
                }),
            minSize: 200,
            sortingFn: sortByStatus,
            meta: {
                stretch: true,
                label: () => t('table.friendList.status'),
                mobile: { slot: 'body' }
            },
            cell: ({ row }) => {
                const status = row.original?.status;
                return (
                    <span class="flex items-center">
                        {status && status !== 'offline' ? (
                            <i class={['x-user-status', statusClass(status), 'mr-1']}></i>
                        ) : null}
                        <span>{row.original?.statusDescription ?? ''}</span>
                    </span>
                );
            }
        },
        {
            id: 'language',
            accessorFn: (row) => row?.$languages,
            header: ({ column }) =>
                sortButton({
                    column,
                    label: () => t('table.friendList.language')
                }),
            size: 130,
            meta: { label: () => t('table.friendList.language'), mobile: { slot: 'footer', class: FOOTER_HIDE_EMPTY } },
            sortingFn: sortByLanguages,
            cell: ({ row }) =>
                isCompactLayout() && !row.original?.$languages?.length ? null : (
                    <div class="flex items-center">
                        {(row.original?.$languages ?? []).map((item) => (
                            <TooltipWrapper key={item.key} side="top" content={`${item.value} (${item.key})`}>
                                <span class={['flags', 'inline-block', 'mr-1', languageClass(item.key)]}></span>
                            </TooltipWrapper>
                        ))}
                    </div>
                )
        },
        {
            id: 'bioLink',
            header: () => t('table.friendList.bioLink'),
            size: 130,
            enableSorting: false,
            meta: { label: () => t('table.friendList.bioLink'), mobile: { slot: 'footer', class: FOOTER_HIDE_EMPTY } },
            cell: ({ row }) =>
                isCompactLayout() && !(row.original?.bioLinks ?? []).filter(Boolean).length ? null : (
                    <div class="flex items-center">
                        {(row.original?.bioLinks ?? []).filter(Boolean).map((link, index) => (
                            <TooltipWrapper key={index} content={String(link)}>
                                <img
                                    src={getFaviconUrl(link)}
                                    class="h-4 w-4 mr-1 align-middle cursor-pointer"
                                    onClick={(event) => {
                                        event.stopPropagation();
                                        openExternalLink(link);
                                    }}
                                    loading="lazy"
                                />
                            </TooltipWrapper>
                        ))}
                    </div>
                )
        },
        {
            id: 'joinCount',
            accessorFn: (row) => row?.$joinCount,
            header: ({ column }) =>
                sortButton({
                    column,
                    label: () => t('table.friendList.joinCount')
                }),
            size: 120,
            sortingFn: sortByNumber((row) => row?.$joinCount ?? 0),
            meta: {
                class: 'text-right',
                label: () => t('table.friendList.joinCount'),
                mobile: { slot: 'footer', class: FOOTER_STAT }
            },
            // PC: TanStack's default cell. Phones: "Joins: 3", or nothing without game log data.
            cell: ({ row, renderValue }) =>
                isCompactLayout()
                    ? compactStat(() => t('table.friendList.joinCount'), row.original?.$joinCount || null)
                    : (renderValue()?.toString?.() ?? null)
        },
        {
            id: 'timeTogether',
            accessorFn: (row) => row?.$timeSpent,
            header: ({ column }) =>
                sortButton({
                    column,
                    label: () => t('table.friendList.timeTogether')
                }),
            size: 140,
            sortingFn: sortByNumber((row) => row?.$timeSpent ?? 0),
            meta: {
                class: 'text-right',
                label: () => t('table.friendList.timeTogether'),
                mobile: { slot: 'footer', class: FOOTER_STAT }
            },
            cell: ({ row }) => {
                const time = row.original?.$timeSpent;
                if (isCompactLayout()) {
                    return compactStat(() => t('table.friendList.timeTogether'), time ? timeToText(time) : null);
                }
                return time ? <span>{timeToText(time)}</span> : null;
            }
        },
        {
            id: 'lastSeen',
            accessorFn: (row) => row?.$lastSeen,
            header: ({ column }) =>
                sortButton({
                    column,
                    label: () => t('table.friendList.lastSeen')
                }),
            size: 170,
            meta: { label: () => t('table.friendList.lastSeen'), mobile: { slot: 'footer', class: FOOTER_STAT } },
            sortingFn: sortByString((row) => row?.$lastSeen ?? ''),
            cell: ({ row }) => {
                const text = formatDateFilter(row.original?.$lastSeen, 'long');
                if (isCompactLayout()) {
                    return compactStat(() => t('table.friendList.lastSeen'), text === '-' ? null : text);
                }
                return <span>{text === '-' ? '' : text}</span>;
            }
        },
        {
            id: 'mutualFriends',
            accessorFn: (row) => row?.$mutualCount,
            header: ({ column }) =>
                sortButton({
                    column,
                    label: () => t('table.friendList.mutualFriends')
                }),
            size: 120,
            sortingFn: sortByNumber((row) => row?.$mutualCount ?? 0),
            meta: {
                class: 'text-right',
                label: () => t('table.friendList.mutualFriends'),
                mobile: { slot: 'footer', class: FOOTER_STAT }
            },
            cell: ({ row }) => {
                const count = row.original?.$mutualCount;
                const optedOut = row.original?.$mutualOptedOut;
                if (!count && !optedOut) return null;
                if (isCompactLayout()) {
                    // Phones: the opted-out note inline instead of in the icon's tooltip.
                    return compactStat(
                        () => t('table.friendList.mutualFriends'),
                        optedOut ? t('table.friendList.mutualOptedOut') : count
                    );
                }
                return (
                    <span class="inline-flex items-center gap-1">
                        {count || null}
                        {optedOut ? (
                            <TooltipWrapper side="top" content={t('table.friendList.mutualOptedOut')}>
                                <EyeOff class="h-3.5 w-3.5 text-muted-foreground" />
                            </TooltipWrapper>
                        ) : null}
                    </span>
                );
            }
        },
        {
            id: 'lastActivity',
            accessorFn: (row) => row?.last_activity,
            header: ({ column }) =>
                sortButton({
                    column,
                    label: () => t('table.friendList.lastActivity')
                }),
            size: 200,
            // Phones: a labelled footer stat like the game log ones (cards never expand: a tap opens the user).
            meta: {
                label: () => t('table.friendList.lastActivity'),
                mobile: { slot: 'footer', class: FOOTER_STAT }
            },
            sortingFn: sortByString((row) => row?.last_activity ?? ''),
            cell: ({ row }) =>
                isCompactLayout() ? (
                    compactStat(() => t('table.friendList.lastActivity'), compactDate(row.original?.last_activity))
                ) : (
                    <span>{formatDateFilter(row.original?.last_activity, 'long')}</span>
                )
        },
        {
            id: 'lastLogin',
            accessorFn: (row) => row?.last_login,
            header: ({ column }) =>
                sortButton({
                    column,
                    label: () => t('table.friendList.lastLogin')
                }),
            size: 200,
            meta: {
                label: () => t('table.friendList.lastLogin'),
                mobile: { slot: 'footer', class: FOOTER_STAT }
            },
            sortingFn: sortByString((row) => row?.last_login ?? ''),
            cell: ({ row }) =>
                isCompactLayout() ? (
                    compactStat(() => t('table.friendList.lastLogin'), compactDate(row.original?.last_login))
                ) : (
                    <span>{formatDateFilter(row.original?.last_login, 'long')}</span>
                )
        },
        {
            id: 'dateJoined',
            accessorFn: (row) => row?.date_joined,
            header: ({ column }) =>
                sortButton({
                    column,
                    label: () => t('table.friendList.dateJoined')
                }),
            size: 120,
            meta: {
                label: () => t('table.friendList.dateJoined'),
                mobile: { slot: 'footer', class: FOOTER_STAT }
            },
            sortingFn: sortByString((row) => row?.date_joined ?? ''),
            cell: ({ row }) =>
                isCompactLayout() ? (
                    compactStat(() => t('table.friendList.dateJoined'), row.original?.date_joined || null)
                ) : (
                    <span>{row.original?.date_joined ?? ''}</span>
                )
        },
        {
            id: 'unfriend',
            header: () => t('table.friendList.unfriend'),
            size: 100,
            enableSorting: false,
            meta: {
                class: 'text-center',
                label: t('table.friendList.unfriend'),
                mobile: { slot: 'actions' }
            },
            cell: ({ row }) =>
                isCompactLayout() ? (
                    <Button
                        variant="ghost"
                        size="icon-sm"
                        class="text-destructive"
                        aria-label={t('table.friendList.unfriend')}
                        onClick={(event) => {
                            event.stopPropagation();
                            onConfirmDeleteFriend?.(row.original?.id);
                        }}
                    >
                        <UserMinus class="h-4 w-4" />
                    </Button>
                ) : (
                    // TODO(icon): verify unfollow icon replacement
                    <UserMinus
                        class="h-4 w-4 text-destructive inline-block"
                        onClick={(event) => {
                            event.stopPropagation();
                            onConfirmDeleteFriend?.(row.original?.id);
                        }}
                    />
                )
        }
    );

    return cols;
};
