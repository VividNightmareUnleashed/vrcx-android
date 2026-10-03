<template>
    <DropdownMenu v-if="hasItems">
        <DropdownMenuTrigger as-child>
            <Button
                variant="ghost"
                size="icon-sm"
                class="shrink-0 text-muted-foreground pointer-coarse:relative pointer-coarse:after:absolute pointer-coarse:after:-inset-1"
                data-testid="game-log-row-menu"
                :aria-label="t('android.main_views.more_actions')"
                @click.stop>
                <EllipsisVertical />
            </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" class="min-w-48">
            <WorldActionMenuItems
                v-if="hasWorld"
                variant="dropdown"
                :can-open-instance-in-game="canOpenInstanceInGame"
                :show-share="true"
                :show-previous-instances="true"
                @view-details="showWorldDialog(parsedLocation.tag)"
                @share="shareWorld"
                @new-instance="showWorldDialog(parsedLocation.tag, parsedLocation.shortName)"
                @self-invite="runNewInstanceSelfInviteFlow(parsedLocation.worldId)"
                @show-previous-instances="showPreviousInstancesInfoDialog(parsedLocation.tag)" />
            <DropdownMenuSeparator v-if="hasWorld && (actions.openUrl || actions.copyText)" />
            <DropdownMenuItem v-if="actions.openUrl" @click="openExternalLink(actions.openUrl)">
                <ExternalLink class="size-4" />
                {{ t('common.actions.open_link') }}
            </DropdownMenuItem>
            <DropdownMenuItem v-if="actions.copyText" @click="copyToClipboard(actions.copyText)">
                <Copy class="size-4" />
                {{ t('common.actions.copy') }}
            </DropdownMenuItem>
        </DropdownMenuContent>
    </DropdownMenu>
</template>

<script setup>
    // Explicit "more" menu for game log rows and sessions in the phone layout: the same items as the PC right-click
    // menus (docs/DESIGN.md §3.3). Long-press still opens the PC context menus.
    import { computed } from 'vue';
    import { Copy, EllipsisVertical, ExternalLink } from 'lucide-vue-next';
    import { storeToRefs } from 'pinia';
    import { useI18n } from 'vue-i18n';

    import { Button } from '../../../components/ui/button';
    import {
        DropdownMenu,
        DropdownMenuContent,
        DropdownMenuItem,
        DropdownMenuSeparator,
        DropdownMenuTrigger
    } from '../../../components/ui/dropdown-menu';
    import WorldActionMenuItems from '../../../components/WorldActionMenuItems.vue';
    import { copyToClipboard, openExternalLink, parseLocation } from '../../../shared/utils';
    import { useInstanceStore, useInviteStore } from '../../../stores';
    import { showWorldDialog } from '../../../coordinators/worldCoordinator';
    import { runNewInstanceSelfInviteFlow } from '../../../coordinators/inviteCoordinator';
    import { getGameLogMenuActions } from '../gameLogMenu';

    const props = defineProps({
        entry: {
            type: Object,
            required: true
        }
    });

    const { t } = useI18n();
    const { showPreviousInstancesInfoDialog } = useInstanceStore();
    const { canOpenInstanceInGame } = storeToRefs(useInviteStore());

    const actions = computed(() => getGameLogMenuActions(props.entry));
    const parsedLocation = computed(() => parseLocation(actions.value.location || ''));
    const hasWorld = computed(() =>
        Boolean(actions.value.location && parsedLocation.value.isRealInstance && parsedLocation.value.worldId)
    );
    const hasItems = computed(() => hasWorld.value || Boolean(actions.value.openUrl || actions.value.copyText));

    function shareWorld() {
        const worldId = parsedLocation.value.worldId;
        if (!worldId) return;
        copyToClipboard(`https://vrchat.com/home/world/${worldId}`, t('message.world.url_copied'));
    }
</script>
