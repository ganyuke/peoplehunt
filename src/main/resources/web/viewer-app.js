const { h, render, Fragment } = window.preact;
const { useState, useEffect, useMemo, useRef } = window.preactHooks;
const html = window.htm.bind(h);

const initialSnapshot = window.PEOPLEHUNT_SNAPSHOT;
const REPORT_ID = window.PEOPLEHUNT_REPORT_ID;
const MC_ICONS = window.PEOPLEHUNT_ICONS || {};
const ICON_KEYS = Object.keys(MC_ICONS);
const MOB_COLOR = '#8b6f47';
const DRAGON_COLOR = '#7c3aed';
const END_CRYSTAL_COLOR = '#06b6d4';
const DEFAULT_LAYERS = Object.freeze({
    players: true,
    projectiles: true,
    mobs: true,
    markers: true,
    damage: true,
    dragon: true
});

const THEME_STORAGE_KEY = 'peoplehunt-theme';

function readThemePreference() {
    const active = document.documentElement.dataset.theme;
    if (active === 'light' || active === 'dark') return active;
    try {
        const stored = window.localStorage.getItem(THEME_STORAGE_KEY);
        if (stored === 'light' || stored === 'dark') return stored;
    } catch (error) {
        // localStorage can be unavailable in hardened browser contexts.
    }
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
}

function applyThemePreference(theme) {
    const next = theme === 'light' ? 'light' : 'dark';
    document.documentElement.dataset.theme = next;
    document.documentElement.style.colorScheme = next;
    try {
        window.localStorage.setItem(THEME_STORAGE_KEY, next);
    } catch (error) {
        // Persisting theme is optional; rendering should continue without it.
    }
    return next;
}


function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
}

function formatTime(ms) {
    if (!Number.isFinite(ms) || ms < 0) ms = 0;
    const totalSec = Math.floor(ms / 1000);
    const min = Math.floor(totalSec / 60);
    const sec = totalSec % 60;
    return `${min}:${sec.toString().padStart(2, '0')}`;
}

function formatMatchDuration(ms) {
    if (!Number.isFinite(ms) || ms < 0) ms = 0;
    const totalSec = Math.floor(ms / 1000);
    const hours = Math.floor(totalSec / 3600);
    const minutes = Math.floor((totalSec % 3600) / 60);
    const sec = totalSec % 60;
    if (hours > 0) return `${hours}h ${String(minutes).padStart(2, '0')}m ${String(sec).padStart(2, '0')}s`;
    if (minutes > 0) return `${minutes}m ${String(sec).padStart(2, '0')}s`;
    return `${sec}s`;
}

function pretty(raw) {
    if (!raw) return '';
    return String(raw)
        .replace(/^minecraft:/, '')
        .replace(/^entity\./, '')
        .replace(/\//g, ' ')
        .replace(/_/g, ' ')
        .replace(/\b\w/g, char => char.toUpperCase());
}

function norm(raw) {
    if (!raw) return null;
    const value = String(raw).trim().toLowerCase();
    return value.includes(':') ? value : `minecraft:${value}`;
}

function rgba(hex, alpha) {
    if (!hex || typeof hex !== 'string') return `rgba(15,23,42,${alpha})`;
    const value = hex.replace('#', '');
    const normalized = value.length === 3 ? value.split('').map(x => x + x).join('') : value;
    const num = parseInt(normalized, 16);
    if (Number.isNaN(num)) return `rgba(15,23,42,${alpha})`;
    return `rgba(${(num >> 16) & 255}, ${(num >> 8) & 255}, ${num & 255}, ${alpha})`;
}

function formatDamage(value) {
    return Number.isFinite(value) ? value.toFixed(1) : '0.0';
}

function formatCoord(value) {
    return Number.isFinite(value) ? Math.round(value) : 0;
}

function formatPercent(value) {
    return `${Math.round(clamp(value, 0, 1) * 100)}%`;
}


function romanNumeral(value) {
    const safe = Math.max(1, Number(value) || 1);
    const numerals = [
        [1000, 'M'],
        [900, 'CM'],
        [500, 'D'],
        [400, 'CD'],
        [100, 'C'],
        [90, 'XC'],
        [50, 'L'],
        [40, 'XL'],
        [10, 'X'],
        [9, 'IX'],
        [5, 'V'],
        [4, 'IV'],
        [1, 'I']
    ];
    let remaining = safe;
    let out = '';
    for (const [amount, token] of numerals) {
        while (remaining >= amount) {
            out += token;
            remaining -= amount;
        }
    }
    return out;
}

function participantStatSummary(currentStats, finalStats) {
    const finalKills = finalStats?.playerKills ?? 0;
    const finalDeaths = finalStats?.deaths ?? 0;
    const finalDealt = finalStats?.playerDamageDealt ?? 0;
    return `K ${currentStats.playerKills} (${finalKills}) / D ${currentStats.deaths} (${finalDeaths}) / ${formatDamage(currentStats.playerDamageDealt)} dealt (${formatDamage(finalDealt)})`;
}

function xpToReachLevel(level) {
    const safe = Math.max(0, Number(level) || 0);
    if (safe <= 16) return (safe * safe) + (6 * safe);
    if (safe <= 31) return Math.floor((2.5 * safe * safe) - (40.5 * safe) + 360);
    return Math.floor((4.5 * safe * safe) - (162.5 * safe) + 2220);
}

function xpNeededForNextLevel(level) {
    const safe = Math.max(0, Number(level) || 0);
    if (safe <= 15) return (2 * safe) + 7;
    if (safe <= 30) return (5 * safe) - 38;
    return (9 * safe) - 158;
}

function experienceState(point) {
    if (!point) {
        return { level: 0, total: 0, current: 0, needed: 0, progress: 0 };
    }
    const level = Math.max(0, Number(point.xpLevel) || 0);
    const needed = Math.max(1, xpNeededForNextLevel(level));
    const rawProgress = clamp(Number(point.experienceProgress) || 0, 0, 1);
    const levelFloor = xpToReachLevel(level);
    const explicitTotal = Number(point.totalExperience);
    const derivedCurrent = Math.round(rawProgress * needed);
    const current = clamp(derivedCurrent, 0, needed);
    const total = Number.isFinite(explicitTotal) && explicitTotal >= levelFloor
        ? explicitTotal
        : levelFloor + current;
    return { level, total, current, needed, progress: rawProgress };
}

function outcomeColor(outcome) {
    const value = String(outcome || '').toUpperCase();
    if (value.includes('HUNTER')) return '#dc2626';
    if (value.includes('RUNNER')) return '#15803d';
    return '#ca8a04';
}

function displayMatchValue(value, fallback = 'None') {
    if (value == null) return fallback;
    const raw = String(value).trim();
    if (!raw) return fallback;
    const formatted = pretty(raw);
    return formatted || fallback;
}

function mineatarUrl(participant) {
    if (!participant) return '';
    const token = participant.uuid || participant.name || 'Steve';
    return `https://api.mineatar.io/face/${encodeURIComponent(token)}`;
}

function avatarFallback(name) {
    return (name || '?').trim().slice(0, 1).toUpperCase() || '?';
}

function playerById(snapshot, uuid) {
    return snapshot?.participants?.find(player => player.uuid === uuid) || null;
}

function statsById(snapshot, uuid) {
    return snapshot?.stats?.find(stat => stat.uuid === uuid) || null;
}

function playerColor(snapshot, uuid) {
    return playerById(snapshot, uuid)?.colorHex || '#64748b';
}

function availableWorld(snapshot) {
    return Array.from(new Set([
        ...(snapshot?.paths || []).map(point => point.world),
        ...(snapshot?.markers || []).map(marker => marker.world),
        ...(snapshot?.deaths || []).map(death => death.location?.world),
        ...(snapshot?.dragon || []).map(sample => sample.world),
        ...(snapshot?.endCrystals || []).map(crystal => crystal.world)
    ].filter(Boolean)));
}

function findPoint(snapshot, selectedPlayer, time) {
    if (!snapshot || !selectedPlayer) return null;
    let last = null;
    for (const point of rawPlayerSeries(snapshot, selectedPlayer)) {
        if (point.offsetMillis <= time) last = point;
    }
    const death = latestDeathBefore(snapshot, selectedPlayer, time);
    if (death && (!last || death.offsetMillis >= last.offsetMillis)) {
        return buildDeathPoint(snapshot, selectedPlayer, death, last);
    }
    return last;
}

function rawPlayerSeries(snapshot, uuid) {
    return (snapshot?.paths || []).filter(point => point.playerUuid === uuid);
}

function playerSeries(snapshot, uuid) {
    return rawPlayerSeries(snapshot, uuid);
}

function latestDeathBefore(snapshot, uuid, time) {
    let last = null;
    for (const death of snapshot?.deaths || []) {
        if (death.victimUuid === uuid && death.offsetMillis <= time) last = death;
    }
    return last;
}

function buildDeathPoint(snapshot, uuid, death, basePoint = null) {
    const participant = playerById(snapshot, uuid);
    const location = death?.location || null;
    return {
        offsetMillis: death?.offsetMillis || 0,
        playerUuid: uuid,
        playerName: death?.victimName || basePoint?.playerName || participant?.name || 'Unknown',
        lifeIndex: basePoint?.lifeIndex || 0,
        role: basePoint?.role || participant?.role || null,
        gameMode: basePoint?.gameMode || 'SURVIVAL',
        isTeleport: false,
        world: location?.world || basePoint?.world || null,
        x: location?.x ?? basePoint?.x ?? 0,
        y: location?.y ?? basePoint?.y ?? 0,
        z: location?.z ?? basePoint?.z ?? 0,
        health: 0,
        maxHealth: basePoint?.maxHealth || 20,
        absorption: 0,
        food: basePoint?.food || 0,
        saturation: basePoint?.saturation || 0,
        xpLevel: death?.xpLevel ?? basePoint?.xpLevel ?? 0,
        totalExperience: basePoint?.totalExperience ?? 0,
        selectedHotbarSlot: basePoint?.selectedHotbarSlot ?? 0,
        experienceProgress: basePoint?.experienceProgress ?? 0,
        inventory: death?.inventory || basePoint?.inventory || [],
        effects: basePoint?.effects || [],
        isDead: true
    };
}

function playerVitalsSeries(snapshot, uuid) {
    const points = rawPlayerSeries(snapshot, uuid);
    if (!points.length && !(snapshot?.deaths || []).some(death => death.victimUuid === uuid)) return points;
    const merged = [...points];
    for (const death of snapshot?.deaths || []) {
        if (death.victimUuid !== uuid) continue;
        const hasExactPoint = points.some(point => Math.abs(point.offsetMillis - death.offsetMillis) <= 1);
        if (hasExactPoint) continue;
        let basePoint = null;
        for (const point of points) {
            if (point.offsetMillis <= death.offsetMillis) basePoint = point;
            else break;
        }
        merged.push(buildDeathPoint(snapshot, uuid, death, basePoint));
    }
    return merged.sort((left, right) => left.offsetMillis - right.offsetMillis);
}

function mapBounds(snapshot, selectedWorld) {
    let minX = Infinity;
    let maxX = -Infinity;
    let minZ = Infinity;
    let maxZ = -Infinity;
    const accept = world => selectedWorld === 'ALL' || !world || world === selectedWorld;
    const push = (x, z, world) => {
        if (!Number.isFinite(x) || !Number.isFinite(z) || !accept(world)) return;
        minX = Math.min(minX, x);
        maxX = Math.max(maxX, x);
        minZ = Math.min(minZ, z);
        maxZ = Math.max(maxZ, z);
    };

    (snapshot?.paths || []).forEach(point => push(point.x, point.z, point.world));
    (snapshot?.markers || []).forEach(marker => push(marker.x, marker.z, marker.world));
    (snapshot?.deaths || []).forEach(death => {
        if (death.location) push(death.location.x, death.location.z, death.location.world);
    });
    (snapshot?.projectiles || []).forEach(projectile => {
        (projectile.points || []).forEach(point => push(point.x, point.z, point.world));
    });
    (snapshot?.mobs || []).forEach(mob => {
        (mob.points || []).forEach(point => push(point.x, point.z, point.world));
    });
    (snapshot?.damage || []).forEach(damage => {
        if (damage.victimLocation) push(damage.victimLocation.x, damage.victimLocation.z, damage.victimLocation.world);
        if (damage.attackerLocation) push(damage.attackerLocation.x, damage.attackerLocation.z, damage.attackerLocation.world);
    });
    (snapshot?.dragon || []).forEach(sample => push(sample.x, sample.z, sample.world));
    (snapshot?.endCrystals || []).forEach(crystal => push(crystal.x, crystal.z, crystal.world));

    if (minX === Infinity) return { minX: -100, maxX: 100, minZ: -100, maxZ: 100 };
    const pad = Math.max(48, Math.min(240, Math.max(maxX - minX, maxZ - minZ) * 0.12));
    return { minX: minX - pad, maxX: maxX + pad, minZ: minZ - pad, maxZ: maxZ + pad };
}

function findIconByParts(parts, excludes = []) {
    const loweredParts = parts.map(part => String(part).toLowerCase()).filter(Boolean);
    const loweredExcludes = excludes.map(part => String(part).toLowerCase()).filter(Boolean);
    const key = ICON_KEYS.find(candidate => {
        const raw = candidate.toLowerCase();
        return loweredParts.every(part => raw.includes(part)) && loweredExcludes.every(part => !raw.includes(part));
    });
    return key ? MC_ICONS[key] : null;
}

function findHudSprite(patterns, excludes = []) {
    const loweredPatterns = patterns.map(pattern => String(pattern).toLowerCase());
    const loweredExcludes = excludes.map(pattern => String(pattern).toLowerCase());
    const key = ICON_KEYS.find(candidate => {
        const raw = candidate.toLowerCase();
        if (!raw.includes('minecraft:ui/')) return false;
        return loweredPatterns.some(pattern => raw.includes(pattern)) && loweredExcludes.every(pattern => !raw.includes(pattern));
    });
    return key ? MC_ICONS[key] : null;
}

const UI_SPRITES = {
    heartFull: findHudSprite(['hud/heart/full', 'heart/full'], ['hardcore', 'absorbing', 'poison', 'wither', 'frozen', 'blink', 'vehicle']) || findIconByParts(['heart', 'full'], ['hardcore', 'absorbing', 'poison', 'wither', 'frozen', 'blink', 'vehicle']),
    heartHalf: findHudSprite(['hud/heart/half', 'heart/half'], ['hardcore', 'absorbing', 'poison', 'wither', 'frozen', 'blink', 'vehicle']) || findIconByParts(['heart', 'half'], ['hardcore', 'absorbing', 'poison', 'wither', 'frozen', 'blink', 'vehicle']),
    heartEmpty: findHudSprite(['hud/heart/container', 'heart/container', 'heart/empty'], ['hardcore', 'absorbing', 'poison', 'wither', 'frozen', 'blink', 'vehicle']) || findIconByParts(['heart', 'container'], ['hardcore', 'absorbing', 'poison', 'wither', 'frozen', 'blink', 'vehicle']),
    foodFull: findHudSprite(['hud/food/full', 'hud/food_full', 'food/full', 'food_full'], ['hunger', 'blink']) || findIconByParts(['food', 'full'], ['hunger', 'blink']),
    foodHalf: findHudSprite(['hud/food/half', 'hud/food_half', 'food/half', 'food_half'], ['hunger', 'blink']) || findIconByParts(['food', 'half'], ['hunger', 'blink']),
    foodEmpty: findHudSprite(['hud/food/empty', 'hud/food_empty', 'food/empty', 'food_empty', 'food/container'], ['hunger', 'blink']) || findIconByParts(['food', 'empty'], ['hunger', 'blink']) || findIconByParts(['food', 'container'], ['hunger', 'blink'])
};

const KIND_ICON = {
    death: 'minecraft:wither_skeleton_skull',
    damage: 'minecraft:iron_sword',
    environment: 'minecraft:grass_block',
    projectile: 'minecraft:arrow',
    travel: 'minecraft:ender_pearl',
    portal: 'minecraft:obsidian',
    dimension_entry: 'minecraft:obsidian',
    jump: 'minecraft:chorus_fruit',
    spawn: 'minecraft:red_bed',
    world_spawn: 'minecraft:compass',
    gamemode: 'minecraft:spyglass',
    milestone: 'minecraft:nether_star',
    advancement: 'minecraft:knowledge_book',
    challenge: 'minecraft:enchanted_book',
    goal: 'minecraft:gold_block',
    food: 'minecraft:cooked_beef',
    effect: 'minecraft:potion',
    totem: 'minecraft:totem_of_undying',
    shield: 'minecraft:shield',
    chat: 'minecraft:writable_book',
    craft: 'minecraft:crafting_table',
    participant: 'minecraft:player_head',
    match: 'minecraft:clock',
    rollback: 'minecraft:recovery_compass',
    dragon: 'minecraft:dragon_head',
    crystal: 'minecraft:end_crystal'
};

const FALLBACK_EMOJI = {
    death: '☠',
    damage: '⚔',
    environment: '◇',
    projectile: '➶',
    travel: '↝',
    portal: '▣',
    spawn: '⌂',
    world_spawn: '⌂',
    food: '🍖',
    effect: '✺',
    totem: '✹',
    shield: '▰',
    chat: '✉',
    craft: '◫',
    dragon: '◈',
    crystal: '✧'
};

function hasMcIconToken(token) {
    if (!token) return false;
    const id = norm(token);
    if (MC_ICONS[id]) return true;
    const logical = id.replace(/^minecraft:/, '');
    return [
        `minecraft:item/${logical}`,
        `minecraft:block/${logical}`,
        `minecraft:mob_effect/${logical}`,
        `minecraft:ui/${logical}`
    ].some(candidate => Boolean(MC_ICONS[candidate]));
}

function entityIconToken(entityType) {
    if (!entityType) return null;
    const raw = String(entityType).toLowerCase().replace(/^minecraft:/, '');
    const headByEntity = {
        zombie: 'minecraft:zombie_head',
        creeper: 'minecraft:creeper_head',
        skeleton: 'minecraft:skeleton_skull',
        wither_skeleton: 'minecraft:wither_skeleton_skull',
        piglin: 'minecraft:piglin_head',
        ender_dragon: 'minecraft:dragon_head'
    };
    const preferred = headByEntity[raw];
    if (preferred && hasMcIconToken(preferred)) return preferred;
    const spawnEgg = `minecraft:${raw}_spawn_egg`;
    if (hasMcIconToken(spawnEgg)) return spawnEgg;
    return preferred || null;
}

function gamemodeIconToken(mode) {
    switch (String(mode || '').toUpperCase()) {
        case 'SURVIVAL':
            return 'minecraft:iron_sword';
        case 'CREATIVE':
            return 'minecraft:grass_block';
        case 'SPECTATOR':
            return 'minecraft:ender_eye';
        case 'ADVENTURE':
            return 'minecraft:map';
        default:
            return 'minecraft:spyglass';
    }
}

function iconSrc(...tokens) {
    // Resolve icon aliases iteratively instead of recursively. Firefox reports the
    // recursive form as "too much recursion" when a heuristic falls back to the
    // same token repeatedly, such as zombie -> zombie_head when that asset is absent.
    const queue = [...tokens];
    const seen = new Set();
    while (queue.length) {
        const token = queue.shift();
        if (!token) continue;
        const id = norm(token);
        if (seen.has(id)) continue;
        seen.add(id);
        if (MC_ICONS[id]) return MC_ICONS[id];
        const logical = id.replace(/^minecraft:/, '');
        const candidates = [
            `minecraft:item/${logical}`,
            `minecraft:block/${logical}`,
            `minecraft:mob_effect/${logical}`,
            `minecraft:ui/${logical}`
        ];
        for (const candidate of candidates) {
            if (MC_ICONS[candidate]) return MC_ICONS[candidate];
        }
        const raw = String(token).toLowerCase();
        if (KIND_ICON[raw] && hasMcIconToken(KIND_ICON[raw])) queue.push(KIND_ICON[raw]);
        if (raw.includes('zombie')) queue.push('minecraft:zombie_head');
        if (raw.includes('skeleton')) queue.push('minecraft:skeleton_skull');
        if (raw.includes('creeper')) queue.push('minecraft:creeper_head');
        if (raw.includes('dragon')) queue.push('minecraft:dragon_head');
        if (raw.includes('piglin')) queue.push('minecraft:piglin_head');
        if (raw.includes('lava')) queue.push('minecraft:lava_bucket');
        if (raw.includes('fire')) queue.push('minecraft:flint_and_steel');
        if (raw.includes('fall')) queue.push('minecraft:feather');
        if (raw.includes('drown')) queue.push('minecraft:water_bucket');
        if (raw.includes('lightning')) queue.push('minecraft:lightning_rod');
    }
    return null;
}

function Icon({ name, kind, className = 'mc-icon', emoji }) {
    const src = iconSrc(name, kind);
    return src
        ? html`<img class=${className} src=${src} alt="" />`
        : html`<span class=${`${className} emoji`}>${emoji || FALLBACK_EMOJI[kind] || '•'}</span>`;
}

function PlayerHead({ participant, large = false, xlarge = false }) {
    const className = `head${large ? ' large' : ''}${xlarge ? ' xlarge' : ''}`;
    const label = participant?.name || 'Unknown';
    const url = mineatarUrl(participant);
    return html`<img class=${className} src=${url} alt=${label} title=${label} onError=${event => {
        event.currentTarget.style.visibility = 'hidden';
        event.currentTarget.parentElement && event.currentTarget.parentElement.setAttribute('data-fallback', avatarFallback(label));
    }} />`;
}

function HudGlyph({ type, state, className = '' }) {
    const sprite = type === 'heart'
        ? (state === 'full' ? UI_SPRITES.heartFull : state === 'half' ? UI_SPRITES.heartHalf : UI_SPRITES.heartEmpty)
        : (state === 'full' ? UI_SPRITES.foodFull : state === 'half' ? UI_SPRITES.foodHalf : UI_SPRITES.foodEmpty);
    const fallback = type === 'heart' ? '♥' : '🍗';
    return sprite
        ? html`<img class=${className} src=${sprite} alt="" />`
        : html`<span class=${`${className} emoji`}>${fallback}</span>`;
}

function HudCell({ type, state }) {
    return html`<span class="hud-cell">
        <${HudGlyph} type=${type} state="empty" className="hud-glyph hud-base" />
        ${state !== 'empty' ? html`<${HudGlyph} type=${type} state=${state} className="hud-glyph hud-fill-glyph" />` : null}
    </span>`;
}

function HudMeter({ type, current, max = 20, numeric }) {
    const slots = type === 'heart' ? Math.max(10, Math.ceil(max / 2)) : 10;
    const units = type === 'heart' ? clamp(current, 0, slots * 2) : clamp(current, 0, 20);
    const states = [];
    for (let index = 0; index < slots; index++) {
        const amount = units - (index * 2);
        states.push(amount >= 2 ? 'full' : amount >= 1 ? 'half' : 'empty');
    }
    return html`<${Fragment}>
        <span class="hud-icons">${states.map(state => html`<${HudCell} type=${type} state=${state} />`)}</span>
        <span class="hud-value">${numeric}</span>
    <//>`;
}

function defaultEventGroup(kind) {
    const value = String(kind || '').toLowerCase();
    if (['damage', 'environment', 'death', 'projectile', 'shield'].includes(value)) return 'combat';
    if (['travel', 'portal', 'dimension_entry', 'jump', 'spawn', 'world_spawn'].includes(value)) return 'travel';
    if (['advancement', 'challenge', 'goal', 'milestone', 'craft', 'food', 'effect', 'totem'].includes(value)) return 'progress';
    if (['chat'].includes(value)) return 'chat';
    return 'system';
}

function eventPriority(kind) {
    const value = String(kind || '').toLowerCase();
    if (value === 'death') return 80;
    if (value === 'damage' || value === 'environment') return 70;
    if (['challenge', 'goal', 'advancement', 'milestone'].includes(value)) return 65;
    if (value === 'projectile') return 60;
    if (['portal', 'dimension_entry', 'travel', 'jump'].includes(value)) return 55;
    if (['food', 'effect', 'totem', 'shield'].includes(value)) return 50;
    return 40;
}

function mergeText(primary, secondary) {
    if (!secondary) return primary || '';
    if (!primary) return secondary;
    if (primary.includes(secondary) || secondary.includes(primary)) return primary.length >= secondary.length ? primary : secondary;
    return `${primary} · ${secondary}`;
}

function createBaseEvent(partial) {
    return {
        id: partial.id,
        dedupeKey: partial.dedupeKey,
        offsetMillis: partial.offsetMillis || 0,
        kind: partial.kind || 'system',
        group: partial.group || defaultEventGroup(partial.kind),
        title: partial.title || pretty(partial.kind),
        description: partial.description || '',
        playerUuid: partial.playerUuid || null,
        playerName: partial.playerName || null,
        subjectName: partial.subjectName || null,
        colorHex: partial.colorHex || null,
        rawName: partial.rawName || null,
        iconToken: partial.iconToken || null,
        world: partial.world || null,
        location: partial.location || null,
        inventory: partial.inventory || null,
        sources: partial.sources ? [...partial.sources] : [],
        damage: partial.damage || null,
        extra: partial.extra || null
    };
}

function pushEvent(store, event) {
    const existing = store.get(event.dedupeKey);
    if (!existing) {
        store.set(event.dedupeKey, event);
        return;
    }
    if (eventPriority(event.kind) > eventPriority(existing.kind)) existing.kind = event.kind;
    existing.group = defaultEventGroup(existing.kind);
    existing.title = existing.title.length >= event.title.length ? existing.title : event.title;
    existing.description = mergeText(existing.description, event.description);
    existing.colorHex = existing.colorHex || event.colorHex;
    existing.rawName = existing.rawName || event.rawName;
    existing.iconToken = existing.iconToken || event.iconToken;
    existing.world = existing.world || event.world;
    existing.location = existing.location || event.location;
    existing.inventory = existing.inventory || event.inventory;
    existing.playerUuid = existing.playerUuid || event.playerUuid;
    existing.playerName = existing.playerName || event.playerName;
    existing.subjectName = existing.subjectName || event.subjectName;
    existing.damage = existing.damage || event.damage;
    existing.extra = existing.extra || event.extra;
    existing.sources.push(...event.sources);
}

function buildUnifiedEvents(snapshot) {
    const store = new Map();
    const participantsByName = new Map((snapshot?.participants || []).map(participant => [participant.name, participant]));
    const advancementTimelineBuckets = new Set((snapshot?.timeline || [])
        .filter(entry => ['advancement', 'challenge', 'goal'].includes(String(entry.kind || '').toLowerCase()) && entry.rawName)
        .map(entry => `${entry.playerUuid || entry.playerName}:${entry.rawName}:${Math.floor(entry.offsetMillis / 1000)}`));

    (snapshot?.damage || []).forEach((damage, index) => {
        const attackerText = damage.attackerName || pretty(damage.attackerEntityType || 'environment');
        const kind = damage.attackerEntityType === 'ENVIRONMENT' ? 'environment' : 'damage';
        const iconToken = damage.weapon || entityIconToken(damage.attackerEntityType) || damage.cause || kind;
        pushEvent(store, createBaseEvent({
            id: `damage-${index}`,
            dedupeKey: `damage:${damage.offsetMillis}:${damage.victimUuid || damage.victimName}:${damage.attackerEntityUuid || damage.attackerUuid || attackerText}:${damage.cause}:${damage.weapon || ''}:${Math.round((damage.damage || 0) * 100)}`,
            offsetMillis: damage.offsetMillis,
            kind,
            title: `${damage.victimName} took ${formatDamage(damage.damage)} damage`,
            description: `${attackerText} · ${pretty(damage.cause)}${damage.weapon ? ` · ${pretty(damage.weapon)}` : ''}`,
            playerUuid: damage.victimUuid,
            playerName: damage.victimName,
            subjectName: attackerText,
            colorHex: damage.attackerUuid ? playerColor(snapshot, damage.attackerUuid) : (kind === 'environment' ? '#64748b' : MOB_COLOR),
            rawName: damage.cause,
            iconToken,
            world: damage.victimLocation?.world || null,
            location: damage.victimLocation || null,
            damage: damage.damage,
            sources: ['damage']
        }));
    });

    (snapshot?.deaths || []).forEach((death, index) => {
        pushEvent(store, createBaseEvent({
            id: `death-${index}`,
            dedupeKey: `death:${death.offsetMillis}:${death.victimUuid || death.victimName}:${death.cause}:${death.killerUuid || death.killerName || ''}`,
            offsetMillis: death.offsetMillis,
            kind: 'death',
            title: `${death.victimName} died`,
            description: `${pretty(death.cause)}${death.killerName ? ` · by ${death.killerName}` : ''}${death.weapon ? ` · ${pretty(death.weapon)}` : ''}`,
            playerUuid: death.victimUuid,
            playerName: death.victimName,
            subjectName: death.killerName || null,
            colorHex: '#dc2626',
            rawName: death.cause,
            iconToken: death.weapon || entityIconToken(death.killerEntityType) || death.cause || 'death',
            world: death.location?.world || null,
            location: death.location || null,
            inventory: death.inventory || null,
            sources: ['death']
        }));
    });

    (snapshot?.chat || []).forEach((chat, index) => {
        const chatKind = String(chat.kind || 'chat').toLowerCase();
        if (chatKind === 'advancement') return;
        const plain = String(chat.plainText || '').trim();
        const advancementMatch = plain.match(/has made the advancement \[(.+)\]/i)
            || plain.match(/has reached the goal \[(.+)\]/i)
            || plain.match(/has completed the challenge \[(.+)\]/i);
        if (advancementMatch) {
            const hasNearbyProgressEvent = (snapshot?.timeline || []).some(entry => {
                const kind = String(entry.kind || '').toLowerCase();
                if (!['advancement', 'challenge', 'goal'].includes(kind)) return false;
                if ((entry.playerUuid || entry.playerName) !== (chat.playerUuid || chat.playerName)) return false;
                return Math.abs(entry.offsetMillis - chat.offsetMillis) <= 1500;
            }) || (snapshot?.milestones || []).some(milestone => {
                if ((milestone.playerUuid || milestone.playerName) !== (chat.playerUuid || chat.playerName)) return false;
                return Math.abs(milestone.offsetMillis - chat.offsetMillis) <= 1500;
            });
            if (hasNearbyProgressEvent) return;
        }
        pushEvent(store, createBaseEvent({
            id: `chat-${index}`,
            dedupeKey: `chat:${chatKind}:${chat.offsetMillis}:${chat.playerUuid || chat.playerName}:${chat.plainText}`,
            offsetMillis: chat.offsetMillis,
            kind: 'chat',
            title: `${chat.playerName || 'Server'} ${chatKind === 'whisper' ? 'whispered' : 'said'}`,
            description: chat.plainText,
            playerUuid: chat.playerUuid,
            playerName: chat.playerName,
            colorHex: playerColor(snapshot, chat.playerUuid),
            iconToken: chatKind === 'whisper' ? 'minecraft:book' : 'chat',
            sources: ['chat']
        }));
    });

    (snapshot?.food || []).forEach((food, index) => {
        pushEvent(store, createBaseEvent({
            id: `food-${index}`,
            dedupeKey: `food:${food.offsetMillis}:${food.playerUuid || food.playerName}:${food.rawName}`,
            offsetMillis: food.offsetMillis,
            kind: 'food',
            title: `${food.playerName} ate ${food.prettyName}`,
            description: `${food.health.toFixed(1)} hp · ${food.food}/20 food · sat ${food.saturation.toFixed(1)}`,
            playerUuid: food.playerUuid,
            playerName: food.playerName,
            colorHex: food.colorHex || playerColor(snapshot, food.playerUuid),
            rawName: food.rawName,
            iconToken: food.rawName || 'food',
            sources: ['food']
        }));
    });

    (snapshot?.effects || []).forEach((effect, index) => {
        const action = String(effect.action || '').toLowerCase();
        const verb = action === 'added' ? 'gained' : action === 'changed' ? 'changed' : 'lost';
        pushEvent(store, createBaseEvent({
            id: `effect-${index}`,
            dedupeKey: `effect:${effect.offsetMillis}:${effect.playerUuid || effect.playerName}:${effect.action}:${effect.rawName}:${effect.amplifier}`,
            offsetMillis: effect.offsetMillis,
            kind: 'effect',
            title: `${effect.playerName} ${verb} ${effect.prettyName}`,
            description: `Level ${effect.amplifier + 1}${effect.durationTicks ? ` · ${Math.ceil(effect.durationTicks / 20)}s` : ''}${effect.cause ? ` · ${pretty(effect.cause)}` : ''}`,
            playerUuid: effect.playerUuid,
            playerName: effect.playerName,
            colorHex: effect.colorHex || playerColor(snapshot, effect.playerUuid),
            rawName: effect.rawName,
            iconToken: effect.rawName || 'effect',
            sources: ['effect']
        }));
    });

    (snapshot?.totems || []).forEach((totem, index) => {
        pushEvent(store, createBaseEvent({
            id: `totem-${index}`,
            dedupeKey: `totem:${totem.offsetMillis}:${totem.playerUuid || totem.playerName}`,
            offsetMillis: totem.offsetMillis,
            kind: 'totem',
            title: `${totem.playerName} activated Totem of Undying`,
            description: 'Cheated death',
            playerUuid: totem.playerUuid,
            playerName: totem.playerName,
            colorHex: totem.colorHex || '#facc15',
            rawName: 'minecraft:totem_of_undying',
            iconToken: 'minecraft:totem_of_undying',
            world: totem.location?.world || null,
            location: totem.location || null,
            sources: ['totem']
        }));
    });

    (snapshot?.blocks || []).forEach((block, index) => {
        pushEvent(store, createBaseEvent({
            id: `block-${index}`,
            dedupeKey: `block:${block.offsetMillis}:${block.playerUuid || block.playerName}:${block.attackerName}:${Math.round((block.blockedDamage || 0) * 100)}`,
            offsetMillis: block.offsetMillis,
            kind: 'shield',
            title: `${block.playerName} blocked ${formatDamage(block.blockedDamage)} damage`,
            description: `${block.attackerName || 'Unknown attacker'}${block.rawName ? ` · ${pretty(block.rawName)}` : ''}`,
            playerUuid: block.playerUuid,
            playerName: block.playerName,
            colorHex: block.colorHex || playerColor(snapshot, block.playerUuid),
            rawName: block.rawName,
            iconToken: block.rawName || 'shield',
            world: block.location?.world || null,
            location: block.location || null,
            sources: ['block']
        }));
    });

    (snapshot?.projectiles || []).forEach((projectile, index) => {
        const start = projectile.points?.[0] || null;
        pushEvent(store, createBaseEvent({
            id: `projectile-${index}`,
            dedupeKey: `projectile:${projectile.launchedAtOffsetMillis}:${projectile.projectileUuid}`,
            offsetMillis: projectile.launchedAtOffsetMillis,
            kind: 'projectile',
            title: `${pretty(projectile.type)} launched`,
            description: `${projectile.shooterName || pretty(projectile.shooterEntityType)}${projectile.endedAtOffsetMillis ? ` · flight ${(projectile.endedAtOffsetMillis - projectile.launchedAtOffsetMillis) / 1000}s` : ''}`,
            playerUuid: projectile.shooterUuid,
            playerName: projectile.shooterName || pretty(projectile.shooterEntityType),
            colorHex: projectile.shooterUuid ? playerColor(snapshot, projectile.shooterUuid) : (projectile.kind === 'hostile' ? MOB_COLOR : '#475569'),
            rawName: projectile.type,
            iconToken: projectile.type,
            world: start?.world || null,
            location: start ? { world: start.world, x: start.x, y: start.y, z: start.z } : null,
            sources: ['projectile']
        }));
    });

    (snapshot?.markers || []).forEach((marker, index) => {
        const kind = marker.kind === 'dimension_entry' ? 'portal' : marker.kind;
        pushEvent(store, createBaseEvent({
            id: `marker-${index}`,
            dedupeKey: `marker:${marker.offsetMillis}:${kind}:${marker.playerUuid || marker.playerName || ''}:${marker.world}:${formatCoord(marker.x)}:${formatCoord(marker.y)}:${formatCoord(marker.z)}:${marker.label || ''}`,
            offsetMillis: marker.offsetMillis,
            kind,
            title: marker.label || pretty(marker.kind),
            description: [marker.playerName, marker.description].filter(Boolean).join(' · '),
            playerUuid: marker.playerUuid,
            playerName: marker.playerName,
            colorHex: marker.colorHex || (kind === 'spawn' ? '#16a34a' : kind === 'portal' ? '#7c3aed' : '#64748b'),
            rawName: marker.kind,
            iconToken: marker.kind,
            world: marker.world,
            location: marker,
            sources: ['marker']
        }));
    });

    (snapshot?.milestones || []).forEach((milestone, index) => {
        const participant = participantsByName.get(milestone.playerName || '');
        const looksLikeAdvancement = Boolean(milestone.rawName && String(milestone.rawName).startsWith('minecraft:') && String(milestone.rawName).includes('/'));
        const advancementBucket = `${milestone.playerUuid || milestone.playerName}:${milestone.rawName}:${Math.floor(milestone.offsetMillis / 1000)}`;
        const hasNearbyAdvancementTimeline = (snapshot?.timeline || []).some(entry => {
            const kind = String(entry.kind || '').toLowerCase();
            if (!['advancement', 'challenge', 'goal'].includes(kind)) return false;
            if ((entry.playerUuid || entry.playerName) !== (milestone.playerUuid || milestone.playerName)) return false;
            if (Math.abs(entry.offsetMillis - milestone.offsetMillis) > 1500) return false;
            if (milestone.rawName && entry.rawName) return entry.rawName === milestone.rawName;
            return String(entry.description || '').trim() === String(milestone.description || '').trim();
        });
        if (looksLikeAdvancement && (advancementTimelineBuckets.has(advancementBucket) || hasNearbyAdvancementTimeline)) return;
        pushEvent(store, createBaseEvent({
            id: `milestone-${index}`,
            dedupeKey: `milestone:${milestone.offsetMillis}:${milestone.playerUuid || milestone.playerName}:${milestone.rawName || milestone.key}:${milestone.description}`,
            offsetMillis: milestone.offsetMillis,
            kind: 'milestone',
            title: milestone.description,
            description: looksLikeAdvancement ? pretty(milestone.rawName) : milestone.key,
            playerUuid: milestone.playerUuid,
            playerName: milestone.playerName,
            colorHex: milestone.colorHex || participant?.colorHex || '#16a34a',
            rawName: milestone.rawName || milestone.key,
            iconToken: milestone.rawName || 'milestone',
            sources: ['milestone']
        }));
    });

    (snapshot?.endCrystals || []).forEach((crystal, index) => {
        pushEvent(store, createBaseEvent({
            id: `crystal-spawn-${index}`,
            dedupeKey: `crystal-spawn:${crystal.crystalUuid}`,
            offsetMillis: crystal.spawnedAtOffsetMillis,
            kind: 'crystal',
            title: 'End crystal tracked',
            description: `Spawned in ${pretty(crystal.world)}`,
            colorHex: END_CRYSTAL_COLOR,
            rawName: 'minecraft:end_crystal',
            iconToken: 'minecraft:end_crystal',
            world: crystal.world,
            location: crystal,
            sources: ['endCrystal']
        }));
        if (Number.isFinite(crystal.destroyedAtOffsetMillis)) {
            pushEvent(store, createBaseEvent({
                id: `crystal-break-${index}`,
                dedupeKey: `crystal-break:${crystal.crystalUuid}`,
                offsetMillis: crystal.destroyedAtOffsetMillis,
                kind: 'crystal',
                title: 'End crystal destroyed',
                description: `Destroyed in ${pretty(crystal.world)}`,
                colorHex: END_CRYSTAL_COLOR,
                rawName: 'minecraft:end_crystal',
                iconToken: 'minecraft:end_crystal',
                world: crystal.world,
                location: crystal,
                sources: ['endCrystal']
            }));
        }
    });

    if ((snapshot?.dragon || []).length) {
        const first = snapshot.dragon[0];
        pushEvent(store, createBaseEvent({
            id: 'dragon-track',
            dedupeKey: `dragon:${first.offsetMillis}`,
            offsetMillis: first.offsetMillis,
            kind: 'dragon',
            title: 'Ender Dragon tracked',
            description: 'Dragon visible on the map.',
            colorHex: DRAGON_COLOR,
            rawName: 'minecraft:ender_dragon',
            iconToken: 'minecraft:dragon_head',
            world: first.world,
            location: first,
            sources: ['dragon']
        }));
    }

    (snapshot?.timeline || []).forEach((entry, index) => {
        const value = String(entry.kind || '').toLowerCase();
        if (['damage', 'death', 'projectile', 'food', 'effect', 'totem', 'shield'].includes(value)) return;
        const kind = value === 'health' ? 'progress' : value;
        let iconToken = entry.rawName || entry.kind;
        if (value === 'gamemode') iconToken = gamemodeIconToken(entry.rawName);
        if (['advancement', 'challenge', 'goal'].includes(value)) iconToken = entry.rawName && entry.rawName.startsWith('minecraft:') ? entry.rawName : KIND_ICON[value];
        const title = `${entry.playerName || 'System'} · ${pretty(entry.kind)}`;
        const dedupeKey = ['advancement', 'challenge', 'goal'].includes(value)
            ? `milestone:${entry.offsetMillis}:${entry.playerUuid || entry.playerName}:${entry.rawName || ''}:${entry.description}`
            : `timeline:${value}:${entry.offsetMillis}:${entry.playerUuid || entry.playerName || ''}:${entry.rawName || ''}:${entry.description}`;
        pushEvent(store, createBaseEvent({
            id: `timeline-${index}`,
            dedupeKey,
            offsetMillis: entry.offsetMillis,
            kind: value,
            title,
            description: entry.description,
            playerUuid: entry.playerUuid,
            playerName: entry.playerName,
            colorHex: entry.colorHex || playerColor(snapshot, entry.playerUuid),
            rawName: entry.rawName,
            iconToken,
            sources: ['timeline']
        }));
    });

    return Array.from(store.values()).sort((left, right) => left.offsetMillis - right.offsetMillis || left.title.localeCompare(right.title));
}

function markersFromEvents(events) {
    return events.map(event => ({
        t: event.offsetMillis,
        kind: event.group,
        title: event.title
    }));
}

function nearestEvent(events, time) {
    if (!events.length) return null;
    let best = events[0];
    let bestDistance = Math.abs(events[0].offsetMillis - time);
    for (const event of events) {
        const distance = Math.abs(event.offsetMillis - time);
        if (distance < bestDistance) {
            best = event;
            bestDistance = distance;
        }
    }
    return best;
}

function previousEventTime(events, time) {
    let candidate = null;
    for (const event of events) {
        if (event.offsetMillis < time - 1) candidate = event.offsetMillis;
    }
    return candidate;
}

function nextEventTime(events, time) {
    for (const event of events) {
        if (event.offsetMillis > time + 1) return event.offsetMillis;
    }
    return null;
}

function currentParticipantStats(snapshot, uuid, time) {
    let deaths = 0;
    let playerKills = 0;
    let playerDamageDealt = 0;
    let playerDamageTaken = 0;
    let nonPlayerDamageTaken = 0;
    for (const death of snapshot?.deaths || []) {
        if (death.offsetMillis > time) continue;
        if (death.victimUuid === uuid) deaths += 1;
        if (death.killerUuid === uuid) playerKills += 1;
    }
    for (const damage of snapshot?.damage || []) {
        if (damage.offsetMillis > time) continue;
        if (damage.attackerUuid === uuid) playerDamageDealt += damage.damage || 0;
        if (damage.victimUuid !== uuid) continue;
        if (damage.attackerUuid || String(damage.attackerEntityType || '').toUpperCase() === 'PLAYER') {
            playerDamageTaken += damage.damage || 0;
        } else {
            nonPlayerDamageTaken += damage.damage || 0;
        }
    }
    return { deaths, playerKills, playerDamageDealt, playerDamageTaken, nonPlayerDamageTaken };
}

function participantCurrentWorld(snapshot, uuid, time) {
    return findPoint(snapshot, uuid, time)?.world || null;
}

function pathAtTime(snapshot, uuid, time) {
    return playerSeries(snapshot, uuid).filter(point => point.offsetMillis <= time);
}

function currentItem(point, slot) {
    return point?.inventory?.find(item => item.slot === slot) || null;
}

function selectedHotbarItem(point) {
    if (!point) return null;
    const held = Number.isFinite(point.selectedHotbarSlot) ? point.selectedHotbarSlot : 0;
    return point.inventory?.find(item => item.slot === held) || null;
}

function buildPolyline(points, width, height, minX, maxX, minY, maxY) {
    if (!points.length) return '';
    const safeMaxX = Math.max(minX + 1, maxX);
    const safeMaxY = Math.max(minY + 1, maxY);
    return points.map(point => {
        const x = ((point.x - minX) / (safeMaxX - minX)) * width;
        const y = height - (((point.y - minY) / (safeMaxY - minY)) * height);
        return `${x.toFixed(2)},${y.toFixed(2)}`;
    }).join(' ');
}

function isInteractiveTarget(target) {
    if (!target) return false;
    const tagName = String(target.tagName || '').toUpperCase();
    return tagName === 'INPUT' || tagName === 'TEXTAREA' || tagName === 'SELECT' || tagName === 'BUTTON' || target.isContentEditable;
}

function useResizeBox(ref) {
    const [box, setBox] = useState({ width: 1, height: 1 });
    const lastBox = useRef({ width: 1, height: 1 });
    useEffect(() => {
        const node = ref.current;
        if (!node || typeof ResizeObserver === 'undefined') return undefined;
        let frame = 0;
        const readAndUpdate = () => {
            frame = 0;
            const rect = node.getBoundingClientRect();
            const next = {
                width: Math.max(1, Math.round(rect.width)),
                height: Math.max(1, Math.round(rect.height))
            };
            const previous = lastBox.current;
            if (previous.width === next.width && previous.height === next.height) return;
            lastBox.current = next;
            setBox(next);
        };
        const schedule = () => {
            if (frame) return;
            frame = requestAnimationFrame(readAndUpdate);
        };
        schedule();
        const observer = new ResizeObserver(schedule);
        observer.observe(node);
        return () => {
            observer.disconnect();
            if (frame) cancelAnimationFrame(frame);
        };
    }, [ref]);
    return box;
}

function App() {
    const [theme, setTheme] = useState(readThemePreference);
    const [snapshot, setSnapshot] = useState(initialSnapshot);
    const [loading, setLoading] = useState(!initialSnapshot && !!REPORT_ID && REPORT_ID !== 'LOCAL_EXPORT');
    const [loadError, setLoadError] = useState(null);
    const duration = snapshot ? Math.max(1, snapshot.metadata.endedAtEpochMillis - snapshot.metadata.startedAtEpochMillis) : 1;
    const [time, setTime] = useState(duration);
    const [playing, setPlaying] = useState(false);
    const [speed, setSpeed] = useState(10);
    const [selectedPlayer, setSelectedPlayer] = useState(initialSnapshot?.metadata?.runnerUuid || initialSnapshot?.participants?.[0]?.uuid || null);
    const [selectedWorld, setSelectedWorld] = useState('ALL');
    const [layers, setLayers] = useState(DEFAULT_LAYERS);
    const [selectedEventId, setSelectedEventId] = useState(null);
    const lastFrameTime = useRef(performance.now());

    useEffect(() => {
        applyThemePreference(theme);
    }, [theme]);


    const togglePlayback = () => {
        if (playing) {
            setPlaying(false);
            return;
        }
        if (time >= duration - 1) {
            setTime(0);
        }
        setPlaying(true);
    };

    useEffect(() => {
        if (snapshot || !REPORT_ID || REPORT_ID === 'LOCAL_EXPORT') return undefined;
        let cancelled = false;
        setLoading(true);
        fetch(`/api/report/${REPORT_ID}`)
            .then(async response => {
                if (!response.ok) {
                    const message = (await response.text()).trim();
                    throw new Error(message || `HTTP ${response.status}`);
                }
                return response.json();
            })
            .then(data => {
                if (cancelled) return;
                setSnapshot(data);
                setLoadError(null);
            })
            .catch(error => {
                if (cancelled) return;
                setLoadError(error?.message || 'Failed to load report data.');
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => {
            cancelled = true;
        };
    }, [snapshot]);

    useEffect(() => {
        if (!snapshot) return;
        const fallback = snapshot.metadata.runnerUuid || snapshot.participants?.[0]?.uuid || null;
        if (!selectedPlayer && fallback) setSelectedPlayer(fallback);
    }, [snapshot, selectedPlayer]);

    useEffect(() => {
        if (time > duration) setTime(duration);
    }, [time, duration]);

    useEffect(() => {
        const onKeyDown = event => {
            if ((event.code !== 'Space' && event.key !== ' ') || isInteractiveTarget(event.target)) return;
            event.preventDefault();
            togglePlayback();
        };
        window.addEventListener('keydown', onKeyDown);
        return () => window.removeEventListener('keydown', onKeyDown);
    }, [togglePlayback]);

    useEffect(() => {
        if (!snapshot || !playing) return undefined;
        let handle;
        const loop = now => {
            const delta = now - lastFrameTime.current;
            lastFrameTime.current = now;
            setTime(current => {
                const next = current + (delta * speed);
                if (next >= duration) {
                    setPlaying(false);
                    return duration;
                }
                return next;
            });
            handle = requestAnimationFrame(loop);
        };
        lastFrameTime.current = performance.now();
        handle = requestAnimationFrame(loop);
        return () => cancelAnimationFrame(handle);
    }, [snapshot, playing, speed, duration]);

    const events = useMemo(() => buildUnifiedEvents(snapshot), [snapshot]);
    const selectedPoint = useMemo(() => findPoint(snapshot, selectedPlayer, time), [snapshot, selectedPlayer, time]);
    const selectedEvent = useMemo(() => nearestEvent(events, time), [events, time]);

    useEffect(() => {
        if (!selectedPoint) return;
        if (selectedWorld === 'ALL') return;
        if (!availableWorld(snapshot).includes(selectedWorld)) setSelectedWorld(selectedPoint.world || 'ALL');
    }, [selectedPoint, selectedWorld, snapshot]);

    if (!snapshot) {
        if (loading) return html`<div class="shell"><div class="viewer-toolbar"><a class="toolbar-link" href="/">Archive</a><div class="toolbar-spacer"></div><button class="theme-toggle-btn ${theme === 'light' ? 'active' : ''}" onClick=${() => setTheme(current => current === 'light' ? 'dark' : 'light')}>${theme === 'light' ? 'Dark mode' : 'Light mode'}</button></div><div class="card empty">Loading report data...</div></div>`;
        return html`<div class="shell"><div class="viewer-toolbar"><a class="toolbar-link" href="/">Archive</a><div class="toolbar-spacer"></div><button class="theme-toggle-btn ${theme === 'light' ? 'active' : ''}" onClick=${() => setTheme(current => current === 'light' ? 'dark' : 'light')}>${theme === 'light' ? 'Dark mode' : 'Light mode'}</button></div><div class="card empty">${loadError || 'No report data available.'}${REPORT_ID ? html`<div class="muted">Report ID: ${REPORT_ID}</div>` : null}</div></div>`;
    }

    return html`
        <div class="shell">
            <div class="viewer-toolbar">
                <a class="toolbar-link" href="/">Archive</a>
                <div class="toolbar-chip">${displayMatchValue(snapshot.metadata.runnerName, 'Unknown')}</div>
                <div class="toolbar-chip">${pretty(snapshot.metadata.outcome)}</div>
                ${REPORT_ID && REPORT_ID !== 'LOCAL_EXPORT' ? html`<div class="toolbar-chip">Report ${REPORT_ID.slice(0, 8)}</div>` : null}
                <div class="toolbar-spacer"></div>
                <button class=${`theme-toggle-btn ${theme === 'light' ? 'active' : ''}`} onClick=${() => setTheme(current => current === 'light' ? 'dark' : 'light')}>${theme === 'light' ? 'Dark mode' : 'Light mode'}</button>
            </div>
            <${MatchInfoCard} meta=${snapshot.metadata} duration=${duration} />
            <${PlaybackControlsCard}
                snapshot=${snapshot}
                selectedPlayer=${selectedPlayer}
                setSelectedPlayer=${setSelectedPlayer}
                selectedWorld=${selectedWorld}
                setSelectedWorld=${setSelectedWorld}
                time=${time}
                speed=${speed}
                setSpeed=${setSpeed}
                layers=${layers}
                setLayers=${setLayers}
                stats=${snapshot.stats}
                duration=${duration}
            />
            <${ScrubberCard}
                time=${time}
                duration=${duration}
                onSeek=${setTime}
                playing=${playing}
                setPlaying=${setPlaying}
                togglePlayback=${togglePlayback}
                speed=${speed}
                setSpeed=${setSpeed}
                events=${events}
            />
            <${MapWidget}
                snapshot=${snapshot}
                time=${time}
                setTime=${setTime}
                selectedWorld=${selectedWorld}
                selectedPlayer=${selectedPlayer}
                layers=${layers}
                selectedEvent=${selectedEvent}
                setSelectedEventId=${setSelectedEventId}
                theme=${theme}
            />
            <${TimelineWidget}
                snapshot=${snapshot}
                events=${events}
                time=${time}
                setTime=${setTime}
                selectedWorld=${selectedWorld}
                selectedPlayer=${selectedPlayer}
                selectedEvent=${selectedEvent}
                setSelectedEventId=${setSelectedEventId}
                setSelectedPlayer=${setSelectedPlayer}
            />
            <${GlobalHealthCard}
                snapshot=${snapshot}
                time=${time}
                setTime=${setTime}
                selectedPlayer=${selectedPlayer}
            />
            <${PlayerStatusCard}
                snapshot=${snapshot}
                time=${time}
                selectedPlayer=${selectedPlayer}
                setSelectedPlayer=${setSelectedPlayer}
            />
            <${InventoryCard}
                snapshot=${snapshot}
                time=${time}
                selectedPlayer=${selectedPlayer}
            />
        </div>`;
}

function MatchInfoCard({ meta, duration }) {
    const rows = [
        ['Runner', displayMatchValue(meta.runnerName, 'Unknown')],
        ['Outcome', pretty(meta.outcome)],
        ['Duration', formatMatchDuration(duration)],
        ['Started', new Date(meta.startedAtEpochMillis).toLocaleString()],
        ['Ended', new Date(meta.endedAtEpochMillis).toLocaleString()],
        ['Selected Kit', displayMatchValue(meta.activeKitId)],
        ['Hunter Inventory Level', displayMatchValue(meta.keepInventoryMode)]
    ];
    return html`<section class="card meta-card">
        <div class="card-header">
            <div class="card-title">Match info</div>
        </div>
        <div class="meta-list">
            ${rows.map(([label, value]) => html`<div class="meta-row">
                <div class="label">${label}</div>
                <div class=${`meta-value ${label === 'Outcome' ? 'outcome-value' : ''}`} style=${label === 'Outcome' ? `color:${outcomeColor(meta.outcome)};` : ''}>${value}</div>
            </div>`)}
        </div>
    </section>`;
}

function PlaybackControlsCard({ snapshot, selectedPlayer, setSelectedPlayer, selectedWorld, setSelectedWorld, time, speed, setSpeed, layers, setLayers, stats, duration }) {
    const worlds = availableWorld(snapshot);
    const toggleLayer = key => setLayers(current => ({ ...current, [key]: !current[key] }));
    return html`<section class="card controls-card">
        <div class="card-header">
            <div class="card-title">Playback controls</div>
            <div class="card-subtitle">${formatTime(time)} / ${formatTime(duration)}</div>
        </div>
        <div class="controls-grid">
            <div class="controls-pane">
                <div class="participant-grid">
                    ${snapshot.participants.map(participant => {
                        const active = participant.uuid === selectedPlayer;
                        const point = findPoint(snapshot, participant.uuid, time);
                        const finalStats = stats.find(entry => entry.uuid === participant.uuid) || { playerKills: 0, deaths: 0, playerDamageDealt: 0 };
                        const currentStats = currentParticipantStats(snapshot, participant.uuid, time);
                        return html`<button
                            class=${`player-pill ${active ? 'active' : ''}`}
                            style=${`background:${active ? rgba(participant.colorHex, 0.14) : ''}; border-color:${active ? participant.colorHex : ''};`}
                            onClick=${() => setSelectedPlayer(participant.uuid)}>
                            <span class="left player-pill-main">
                                <${PlayerHead} participant=${participant} />
                                <span class="stack">
                                    <strong class="player-name-line">${participant.name} (${pretty(participant.role)}${point ? ` · ${pretty(point.gameMode)}` : ''})</strong>
                                    <span class="player-stat-line">${participantStatSummary(currentStats, finalStats)}</span>
                                </span>
                            </span>
                            <span class="right">
                                <span class="player-swatch" style=${`background:${participant.colorHex};`}></span>
                            </span>
                        </button>`;
                    })}
                </div>
            </div>
            <div class="controls-pane">
                <div class="control-stack">
                    <div class="control-box">
                        <div class="label">Dimension</div>
                        <div class="segment-row">
                            <button class=${`segment ${selectedWorld === 'ALL' ? 'active' : ''}`} onClick=${() => setSelectedWorld('ALL')}>All</button>
                            ${worlds.map(world => html`<button class=${`segment ${selectedWorld === world ? 'active' : ''}`} onClick=${() => setSelectedWorld(world)}>${pretty(world)}</button>`)}
                        </div>
                    </div>
                    <div class="control-box">
                        <div class="label">Speed</div>
                        <div class="search-row">
                            <select class="select" value=${speed} onChange=${event => setSpeed(Number(event.target.value))}>
                                <option value="1">1x</option>
                                <option value="5">5x</option>
                                <option value="10">10x</option>
                                <option value="30">30x</option>
                                <option value="60">60x</option>
                            </select>
                        </div>
                    </div>
                    <div class="control-box">
                        <div class="label">Map overlays</div>
                        <div class="layer-row">
                            ${Object.entries(layers).map(([key, enabled]) => html`<button class=${`layer-pill ${enabled ? 'active' : ''}`} onClick=${() => toggleLayer(key)}>${pretty(key)}</button>`)}
                        </div>
                    </div>
                </div>
            </div>
        </div>
    </section>`;
}

function ScrubberCard({ time, duration, onSeek, playing, setPlaying, togglePlayback, speed, setSpeed, events }) {
    const [pinned, setPinned] = useState(false);
    const markers = useMemo(() => markersFromEvents(events), [events]);
    const focused = nearestEvent(events, time);
    const prev = previousEventTime(events, time);
    const next = nextEventTime(events, time);
    return html`<section class=${`card scrubber-card ${pinned ? 'pinned' : ''}`}>
        <div class="card-header">
            <div class="card-title">Scrubber</div>
            <div class="card-header-actions">
                <div class="card-subtitle">${focused ? focused.title : `${markers.length} markers`}</div>
                <button class=${`mini-btn ${pinned ? 'active' : ''}`} onClick=${() => setPinned(current => !current)}>${pinned ? 'Unpin' : 'Pin'}</button>
            </div>
        </div>
        <div class="scrubber-body">
            <button class="play-btn" onClick=${togglePlayback}>${playing ? '❚❚' : '▶'}</button>
            <div class="track">
                <div class="track-bg"></div>
                <div class="track-fill" style=${`width:${clamp((time / duration) * 100, 0, 100)}%;`}></div>
                ${markers.map((marker, index) => html`<button
                    class=${`track-marker ${marker.kind}`}
                    key=${`${marker.kind}-${index}`}
                    style=${`left:${clamp((marker.t / duration) * 100, 0, 100)}%;`}
                    title=${`${formatTime(marker.t)} ${marker.title}`}
                    onClick=${event => {
                        event.stopPropagation();
                        onSeek(marker.t);
                        setPlaying(false);
                    }}></button>`)}
                <input
                    type="range"
                    min="0"
                    max=${duration}
                    value=${time}
                    onInput=${event => {
                        setPlaying(false);
                        onSeek(Number(event.target.value));
                    }} />
            </div>
            <div class="time-readout">${formatTime(time)} <span>/ ${formatTime(duration)}</span></div>
            <div class="scrubber-meta">
                <div class="scrubber-jumps">
                    <button class="mini-btn" disabled=${prev == null} onClick=${() => prev != null && (setPlaying(false), onSeek(prev))}>◀</button>
                    <button class="mini-btn" disabled=${next == null} onClick=${() => next != null && (setPlaying(false), onSeek(next))}>▶</button>
                </div>
                <select class="select" value=${speed} onChange=${event => setSpeed(Number(event.target.value))}>
                    <option value="1">1x</option>
                    <option value="5">5x</option>
                    <option value="10">10x</option>
                    <option value="30">30x</option>
                    <option value="60">60x</option>
                </select>
            </div>
        </div>
    </section>`;
}

function MapWidget({ snapshot, time, setTime, selectedWorld, selectedPlayer, layers, selectedEvent, setSelectedEventId, theme }) {
    const canvasRef = useRef(null);
    const wrapRef = useRef(null);
    const hitRef = useRef([]);
    const [hover, setHover] = useState(null);
    const bounds = useMemo(() => mapBounds(snapshot, selectedWorld), [snapshot, selectedWorld]);
    const box = useResizeBox(wrapRef);

    useEffect(() => {
        const canvas = canvasRef.current;
        if (!canvas) return;
        const width = Math.max(1, Math.floor(box.width));
        const height = Math.max(1, Math.floor(box.height));
        const ratio = window.devicePixelRatio || 1;
        canvas.width = Math.floor(width * ratio);
        canvas.height = Math.floor(height * ratio);
        const ctx = canvas.getContext('2d');
        const rootStyle = window.getComputedStyle(document.documentElement);
        const mapGrid = rootStyle.getPropertyValue('--map-grid').trim() || 'rgba(214, 222, 232, 0.18)';
        ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
        ctx.clearRect(0, 0, width, height);

        const scaleX = width / Math.max(1, bounds.maxX - bounds.minX);
        const scaleZ = height / Math.max(1, bounds.maxZ - bounds.minZ);
        const scale = Math.min(scaleX, scaleZ);
        const centerX = (bounds.maxX + bounds.minX) / 2;
        const centerZ = (bounds.maxZ + bounds.minZ) / 2;
        const toCanvas = (x, z) => ({
            x: (width / 2) + ((x - centerX) * scale),
            y: (height / 2) + ((z - centerZ) * scale)
        });
        const accept = world => selectedWorld === 'ALL' || !world || world === selectedWorld;
        const hits = [];

        ctx.strokeStyle = mapGrid;
        ctx.lineWidth = 1;
        ctx.beginPath();
        for (let x = 0; x < width; x += 32) {
            ctx.moveTo(x, 0);
            ctx.lineTo(x, height);
        }
        for (let y = 0; y < height; y += 32) {
            ctx.moveTo(0, y);
            ctx.lineTo(width, y);
        }
        ctx.stroke();

        const drawPath = (points, color, widthPx, dashed) => {
            if (!points.length) return;
            ctx.save();
            ctx.strokeStyle = color;
            ctx.lineWidth = widthPx;
            ctx.lineCap = 'square';
            ctx.lineJoin = 'miter';
            ctx.setLineDash(dashed || []);
            ctx.beginPath();
            let previous = null;
            points.forEach(point => {
                const target = toCanvas(point.x, point.z);
                if (!previous || point.world !== previous.world || point.isTeleport) ctx.moveTo(target.x, target.y);
                else ctx.lineTo(target.x, target.y);
                previous = point;
            });
            ctx.stroke();
            ctx.restore();
        };

        if (layers.players) {
            const grouped = new Map();
            (snapshot.paths || []).filter(point => point.offsetMillis <= time && accept(point.world)).forEach(point => {
                if (!grouped.has(point.playerUuid)) grouped.set(point.playerUuid, []);
                grouped.get(point.playerUuid).push(point);
            });
            grouped.forEach((points, uuid) => {
                const color = playerColor(snapshot, uuid);
                drawPath(points, color, uuid === selectedPlayer ? 3.5 : 2.5, null);
                const last = points[points.length - 1];
                const marker = toCanvas(last.x, last.z);
                const size = uuid === selectedPlayer ? 10 : 8;
                ctx.fillStyle = color;
                ctx.fillRect(marker.x - (size / 2), marker.y - (size / 2), size, size);
                ctx.strokeStyle = '#0d0f11';
                ctx.lineWidth = 1;
                ctx.strokeRect(marker.x - (size / 2), marker.y - (size / 2), size, size);
                hits.push({
                    x: marker.x,
                    y: marker.y,
                    r: 11,
                    time: last.offsetMillis,
                    eventId: null,
                    title: `${last.playerName}${last.gameMode === 'SPECTATOR' ? ' (Spectator)' : ''}`,
                    body: `${pretty(last.world)} · ${formatCoord(last.x)}, ${formatCoord(last.y)}, ${formatCoord(last.z)} · ${last.health.toFixed(1)} hp`,
                    kind: 'travel'
                });
            });
        }

        if (layers.projectiles) {
            (snapshot.projectiles || []).forEach(projectile => {
                const points = (projectile.points || []).filter(point => point.offsetMillis <= time && accept(point.world));
                if (!points.length) return;
                const color = projectile.shooterUuid ? playerColor(snapshot, projectile.shooterUuid) : (projectile.kind === 'hostile' ? MOB_COLOR : '#475569');
                drawPath(points, color, 1.75, [2, 4]);
                const last = points[points.length - 1];
                const target = toCanvas(last.x, last.z);
                ctx.fillStyle = color;
                ctx.fillRect(target.x - 3, target.y - 3, 6, 6);
                hits.push({
                    x: target.x,
                    y: target.y,
                    r: 10,
                    time: projectile.launchedAtOffsetMillis,
                    eventId: null,
                    title: `${pretty(projectile.type)} projectile`,
                    body: `Shooter: ${projectile.shooterName || pretty(projectile.shooterEntityType)}`,
                    kind: 'projectile'
                });
            });
        }

        if (layers.mobs) {
            (snapshot.mobs || []).forEach(mob => {
                const points = (mob.points || []).filter(point => point.offsetMillis <= time && accept(point.world));
                if (!points.length) return;
                drawPath(points, MOB_COLOR, 2, [6, 4]);
                const last = points[points.length - 1];
                const target = toCanvas(last.x, last.z);
                ctx.fillStyle = MOB_COLOR;
                ctx.fillRect(target.x - 4, target.y - 4, 8, 8);
                hits.push({
                    x: target.x,
                    y: target.y,
                    r: 10,
                    time: last.offsetMillis,
                    title: `${pretty(mob.entityType)} path`,
                    body: mob.targetPlayerName ? `Targeting ${mob.targetPlayerName}` : 'Tracked hostile mob',
                    kind: 'damage'
                });
            });
        }

        if (layers.damage) {
            (snapshot.damage || []).filter(damage => damage.offsetMillis <= time && damage.victimLocation && accept(damage.victimLocation.world)).forEach(damage => {
                const victim = toCanvas(damage.victimLocation.x, damage.victimLocation.z);
                const recent = (time - damage.offsetMillis) < 12000;
                ctx.save();
                ctx.globalAlpha = recent ? 0.9 : 0.34;
                ctx.fillStyle = damage.attackerEntityType === 'ENVIRONMENT' ? '#0891b2' : '#dc2626';
                ctx.fillRect(victim.x - (recent ? 4 : 2), victim.y - (recent ? 4 : 2), recent ? 8 : 4, recent ? 8 : 4);
                if (damage.attackerLocation && damage.attackerLocation.world === damage.victimLocation.world && accept(damage.attackerLocation.world)) {
                    const attacker = toCanvas(damage.attackerLocation.x, damage.attackerLocation.z);
                    ctx.strokeStyle = ctx.fillStyle;
                    ctx.lineWidth = 1;
                    ctx.setLineDash([2, 3]);
                    ctx.beginPath();
                    ctx.moveTo(attacker.x, attacker.y);
                    ctx.lineTo(victim.x, victim.y);
                    ctx.stroke();
                }
                ctx.restore();
                hits.push({
                    x: victim.x,
                    y: victim.y,
                    r: 8,
                    time: damage.offsetMillis,
                    title: `${damage.victimName} took ${formatDamage(damage.damage)} damage`,
                    body: `${damage.attackerName || 'Environment'} · ${pretty(damage.cause)}`,
                    kind: damage.attackerEntityType === 'ENVIRONMENT' ? 'environment' : 'damage'
                });
            });
        }

        if (layers.markers) {
            (snapshot.markers || []).filter(marker => marker.offsetMillis <= time && accept(marker.world)).forEach(marker => {
                const target = toCanvas(marker.x, marker.z);
                const color = marker.kind === 'portal' || marker.kind === 'dimension_entry'
                    ? '#7c3aed'
                    : marker.kind === 'spawn' || marker.kind === 'world_spawn'
                        ? '#16a34a'
                        : marker.colorHex || '#2563eb';
                ctx.fillStyle = color;
                ctx.fillRect(target.x - 6, target.y - 6, 12, 12);
                ctx.strokeStyle = '#0d0f11';
                ctx.lineWidth = 1;
                ctx.strokeRect(target.x - 6, target.y - 6, 12, 12);
                hits.push({
                    x: target.x,
                    y: target.y,
                    r: 12,
                    time: marker.offsetMillis,
                    title: marker.label || pretty(marker.kind),
                    body: [marker.playerName, marker.description].filter(Boolean).join(' · '),
                    kind: marker.kind === 'dimension_entry' ? 'portal' : marker.kind
                });
            });
        }

        (snapshot.deaths || []).filter(death => death.offsetMillis <= time && death.location && accept(death.location.world)).forEach(death => {
            const target = toCanvas(death.location.x, death.location.z);
            ctx.strokeStyle = '#dc2626';
            ctx.lineWidth = 2;
            ctx.beginPath();
            ctx.moveTo(target.x - 8, target.y - 8);
            ctx.lineTo(target.x + 8, target.y + 8);
            ctx.moveTo(target.x + 8, target.y - 8);
            ctx.lineTo(target.x - 8, target.y + 8);
            ctx.stroke();
            hits.push({
                x: target.x,
                y: target.y,
                r: 12,
                time: death.offsetMillis,
                title: `${death.victimName} died`,
                body: `${pretty(death.cause)}${death.killerName ? ` · ${death.killerName}` : ''}`,
                kind: 'death'
            });
        });

        if (layers.dragon) {
            const dragonPoints = (snapshot.dragon || []).filter(sample => sample.offsetMillis <= time && accept(sample.world));
            if (dragonPoints.length) {
                drawPath(dragonPoints, DRAGON_COLOR, 2, [10, 6]);
                const last = dragonPoints[dragonPoints.length - 1];
                const target = toCanvas(last.x, last.z);
                ctx.fillStyle = DRAGON_COLOR;
                ctx.fillRect(target.x - 5, target.y - 5, 10, 10);
                hits.push({
                    x: target.x,
                    y: target.y,
                    r: 12,
                    time: last.offsetMillis,
                    title: 'Ender Dragon',
                    body: `${last.health.toFixed(1)} / ${last.maxHealth.toFixed(1)} hp`,
                    kind: 'dragon'
                });
            }
            (snapshot.endCrystals || []).filter(crystal => crystal.spawnedAtOffsetMillis <= time && (!Number.isFinite(crystal.destroyedAtOffsetMillis) || crystal.destroyedAtOffsetMillis >= time) && accept(crystal.world)).forEach(crystal => {
                const target = toCanvas(crystal.x, crystal.z);
                ctx.fillStyle = END_CRYSTAL_COLOR;
                ctx.beginPath();
                ctx.moveTo(target.x, target.y - 6);
                ctx.lineTo(target.x + 6, target.y);
                ctx.lineTo(target.x, target.y + 6);
                ctx.lineTo(target.x - 6, target.y);
                ctx.closePath();
                ctx.fill();
                hits.push({
                    x: target.x,
                    y: target.y,
                    r: 10,
                    time: crystal.spawnedAtOffsetMillis,
                    title: 'End crystal',
                    body: `Alive at ${pretty(crystal.world)}`,
                    kind: 'crystal'
                });
            });
        }

        if (selectedEvent?.location && accept(selectedEvent.location.world)) {
            const target = toCanvas(selectedEvent.location.x, selectedEvent.location.z);
            ctx.strokeStyle = '#0f172a';
            ctx.lineWidth = 1;
            ctx.setLineDash([4, 4]);
            ctx.beginPath();
            ctx.arc(target.x, target.y, 12, 0, Math.PI * 2);
            ctx.stroke();
        }

        hitRef.current = hits;
    }, [snapshot, time, selectedWorld, selectedPlayer, layers, selectedEvent, bounds, box, theme]);

    const onMouseMove = event => {
        const rect = event.currentTarget.getBoundingClientRect();
        const x = event.clientX - rect.left;
        const y = event.clientY - rect.top;
        const hit = hitRef.current.find(entry => Math.hypot(entry.x - x, entry.y - y) <= entry.r);
        if (!hit) {
            setHover(null);
            return;
        }
        setHover({ ...hit, left: x + 14, top: y + 14 });
    };

    const onClick = event => {
        const rect = event.currentTarget.getBoundingClientRect();
        const x = event.clientX - rect.left;
        const y = event.clientY - rect.top;
        const hit = hitRef.current.find(entry => Math.hypot(entry.x - x, entry.y - y) <= entry.r);
        if (!hit) return;
        setTime(hit.time);
        if (hit.eventId) setSelectedEventId(hit.eventId);
    };

    return html`<section class="card map-card">
        <div class="card-header">
            <div class="card-title">Map of paths</div>
            <div class="card-subtitle">players, mobs, projectiles, deaths, portals, spawn</div>
        </div>
        <div class="map-shell">
            <div class="map-toolbar">
                <div class="legend-row">
                    <span class="legend-chip" style="color:${playerColor(snapshot, selectedPlayer)}"><span class="legend-line"></span>Selected player</span>
                    <span class="legend-chip" style="color:${MOB_COLOR}"><span class="legend-line"></span>Mobs</span>
                    <span class="legend-chip" style="color:${DRAGON_COLOR}"><span class="legend-line"></span>Dragon</span>
                    <span class="legend-chip"><span class="legend-dot" style="background:#dc2626"></span>Death</span>
                    <span class="legend-chip"><span class="legend-dot" style="background:#7c3aed"></span>Portal</span>
                    <span class="legend-chip"><span class="legend-dot" style="background:#16a34a"></span>Spawn</span>
                </div>
                <div class="coord-note">${selectedWorld === 'ALL' ? 'All dimensions' : pretty(selectedWorld)}</div>
            </div>
            <div ref=${wrapRef} class="map-wrap" onMouseMove=${onMouseMove} onMouseLeave=${() => setHover(null)} onClick=${onClick}>
                <canvas ref=${canvasRef}></canvas>
                ${hover ? html`<div class="map-popover" style=${`left:${hover.left}px; top:${hover.top}px;`}>
                    <div class="map-popover-title"><${Icon} kind=${hover.kind} />${hover.title}</div>
                    <div>${hover.body}</div>
                    <div class="coord-note">Jump to ${formatTime(hover.time)}</div>
                </div>` : null}
            </div>
        </div>
    </section>`;
}

function GlobalHealthCard({ snapshot, time, setTime, selectedPlayer }) {
    const chartRef = useRef(null);
    const box = useResizeBox(chartRef);
    const series = useMemo(() => snapshot.participants.map(participant => ({
        participant,
        points: playerVitalsSeries(snapshot, participant.uuid).map(point => ({ x: point.offsetMillis, y: point.health, raw: point }))
    })), [snapshot]);
    const maxHealth = useMemo(() => Math.max(20, ...series.flatMap(line => line.points.map(point => point.raw.maxHealth || 20))), [series]);
    const width = Math.max(1, box.width - 20);
    const height = Math.max(160, box.height - 16);
    const cursorX = (time / Math.max(1, snapshot.metadata.endedAtEpochMillis - snapshot.metadata.startedAtEpochMillis)) * width;
    const onPointer = event => {
        const rect = event.currentTarget.getBoundingClientRect();
        const next = clamp(((event.clientX - rect.left) / Math.max(1, rect.width)) * (snapshot.metadata.endedAtEpochMillis - snapshot.metadata.startedAtEpochMillis), 0, snapshot.metadata.endedAtEpochMillis - snapshot.metadata.startedAtEpochMillis);
        setTime(next);
    };
    return html`<section class="card health-card">
        <div class="card-header">
            <div class="card-title">Global participant health</div>
            <div class="card-subtitle">click chart to scrub</div>
        </div>
        <div class="chart-wrap" ref=${chartRef} onClick=${onPointer}>
            <svg viewBox=${`0 0 ${width} ${height}`} preserveAspectRatio="none">
                <g class="chart-grid">
                    ${[0, 0.25, 0.5, 0.75, 1].map(step => html`<line x1="0" y1=${height - (step * height)} x2=${width} y2=${height - (step * height)}></line>`)}
                </g>
                ${series.map(line => html`<polyline
                    fill="none"
                    stroke=${line.participant.colorHex}
                    stroke-width=${line.participant.uuid === selectedPlayer ? 2.5 : 1.7}
                    points=${buildPolyline(line.points, width, height, 0, snapshot.metadata.endedAtEpochMillis - snapshot.metadata.startedAtEpochMillis, 0, maxHealth)}></polyline>`)}
                <line class="chart-cursor" x1=${cursorX} y1="0" x2=${cursorX} y2=${height}></line>
            </svg>
        </div>
        <div class="health-footer">
            <div class="legend-grid">
                ${series.map(line => {
                    const current = findPoint(snapshot, line.participant.uuid, time);
                    return html`<div class="participant-inline" style=${`border-left: 4px solid ${line.participant.colorHex}; padding-left: 8px;`}>
                        <${PlayerHead} participant=${line.participant} />
                        <div>
                            <strong>${line.participant.name}</strong>
                            <span>${current ? `${current.health.toFixed(1)} / ${current.maxHealth.toFixed(1)} hp` : 'No sample'}</span>
                        </div>
                    </div>`;
                })}
            </div>
            
        </div>
    </section>`;
}

function PlayerStatusCard({ snapshot, time, selectedPlayer, setSelectedPlayer }) {
    const point = useMemo(() => findPoint(snapshot, selectedPlayer, time), [snapshot, selectedPlayer, time]);
    const participant = playerById(snapshot, selectedPlayer);
    const currentStats = useMemo(() => currentParticipantStats(snapshot, selectedPlayer, time), [snapshot, selectedPlayer, time]);
    const series = useMemo(() => playerVitalsSeries(snapshot, selectedPlayer).map(sample => ({ x: sample.offsetMillis, y: sample.health, hunger: sample.food })), [snapshot, selectedPlayer]);
    const exp = useMemo(() => experienceState(point), [point]);
    const sparkWidth = 320;
    const sparkHeight = 64;
    const xpRatio = clamp(Number(point?.experienceProgress) || exp.progress || 0, 0, 1);
    const sparkHealth = buildPolyline(series.map(sample => ({ x: sample.x, y: sample.y })), sparkWidth, sparkHeight, 0, snapshot.metadata.endedAtEpochMillis - snapshot.metadata.startedAtEpochMillis, 0, Math.max(20, ...(series.map(sample => sample.y))));
    const sparkFood = buildPolyline(series.map(sample => ({ x: sample.x, y: sample.hunger })), sparkWidth, sparkHeight, 0, snapshot.metadata.endedAtEpochMillis - snapshot.metadata.startedAtEpochMillis, 0, 20);

    return html`<section class="card status-card">
        <div class="card-header">
            <div class="card-title">Selected player</div>
            <div class="card-subtitle">${formatTime(time)}</div>
        </div>
        <div class="status-body">
            ${point ? html`
                <div class="status-top">
                    <div>
                        <${PlayerHead} participant=${participant} xlarge=${true} />
                    </div>
                    <div class="status-section">
                        <div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap;">
                            <span class="metric-value" style="font-size:24px; color:${participant?.colorHex || '#111827'};">${participant?.name || point.playerName}</span>
                            <span class="role-chip">${pretty(participant?.role || point.role)}</span>
                            <span class="role-chip"><${Icon} name=${gamemodeIconToken(point.gameMode)} kind="gamemode" />${pretty(point.gameMode)}</span>
                        </div>
                        <div class="status-note">${pretty(point.world)} · ${formatCoord(point.x)}, ${formatCoord(point.y)}, ${formatCoord(point.z)}</div>
                    </div>
                </div>
                <div class="status-meta">
                    <div class="info-box experience-box">
                        <div class="label">Experience</div>
                        <div class="value">Level ${exp.level} · ${exp.total} total</div>
                        <div class="meter"><span style=${`width:${formatPercent(xpRatio)};`}></span></div>
                        <div class="muted">${exp.current} / ${exp.needed} to next level</div>
                    </div>
                    <div class="info-box"><div class="label">Combat</div><div class="value">K ${currentStats.playerKills} · D ${currentStats.deaths}</div><div class="muted">${formatDamage(currentStats.playerDamageDealt)} dealt · ${formatDamage(currentStats.playerDamageTaken + currentStats.nonPlayerDamageTaken)} taken</div></div>
                    <div class="info-box"><div class="label">Dimension</div><div class="value">${pretty(point.world)}</div><div class="muted">Life ${point.lifeIndex}</div></div>
                </div>
                <div class="status-section">
                    <div class="label">Vitals</div>
                    <div class="hud-row">
                        <span class="hud-label">Health</span>
                        <${HudMeter} type="heart" current=${point.health} max=${point.maxHealth} numeric=${`${point.health.toFixed(1)} / ${point.maxHealth.toFixed(1)}`} />
                    </div>
                    <div class="hud-row">
                        <span class="hud-label">Absorption</span>
                        <span class="hud-text-value">${point.absorption.toFixed(1)} hp</span>
                    </div>
                    <div class="hud-row">
                        <span class="hud-label">Hunger</span>
                        <${HudMeter} type="food" current=${point.food} max=${20} numeric=${`${point.food}/20`} />
                    </div>
                    <div class="hud-row">
                        <span class="hud-label">Saturation</span>
                        <span class="hud-text-value">${point.saturation.toFixed(1)}</span>
                    </div>
                </div>
                <div class="status-section">
                    <div class="label">Health & hunger over time</div>
                    <svg class="sparkline" viewBox="0 0 320 64" preserveAspectRatio="none">
                        <polyline fill="none" stroke=${participant?.colorHex || '#2563eb'} stroke-width="2" points=${sparkHealth}></polyline>
                        <polyline fill="none" stroke="#d97706" stroke-width="2" stroke-dasharray="4 4" points=${sparkFood}></polyline>
                        <line class="chart-cursor" x1=${(time / Math.max(1, snapshot.metadata.endedAtEpochMillis - snapshot.metadata.startedAtEpochMillis)) * sparkWidth} y1="0" x2=${(time / Math.max(1, snapshot.metadata.endedAtEpochMillis - snapshot.metadata.startedAtEpochMillis)) * sparkWidth} y2="64"></line>
                    </svg>
                </div>
                <div class="status-section">
                    <div class="label">Effects</div>
                    ${(point.effects || []).length
                        ? html`<div class="effects-grid">${point.effects.map(effect => html`<span class="effect-pill" title=${`${effect.prettyName} ${effect.amplifier + 1} · ${Math.ceil(effect.durationTicks / 20)}s`}><${Icon} name=${effect.rawType} kind="effect" />${effect.prettyName} ${effect.amplifier + 1}</span>`)}</div>`
                        : html`<div class="muted">No active effects.</div>`}
                </div>
            ` : html`<div class="empty-state">No sample yet at this time.</div>`}
        </div>
    </section>`;
}

function ItemSlot({ item, label = null, hotbar = false, selected = false, smallLabel = false, playerColorHex = null, onHover = null, onLeave = null }) {
    return html`<div class="slot-wrap">
        <div class=${`slot ${item ? 'filled' : ''} ${hotbar ? 'hotbar-slot' : ''} ${selected ? 'selected-slot' : ''} ${item?.enchanted ? 'enchanted' : ''} ${playerColorHex ? 'player-color' : ''}`}
             style=${playerColorHex ? `--slot-player-color:${playerColorHex};` : ''}
             onMouseEnter=${event => item && onHover && onHover(event, item, label)}
             onMouseLeave=${() => onLeave && onLeave()}>
            ${item
                ? html`
                    <${Icon} name=${item.rawId} className="item-icon" />
                    ${item.amount > 1 ? html`<span class="slot-count">${item.amount}</span>` : null}`
                : null}
        </div>
        ${label ? html`<div class="slot-label" style=${smallLabel ? 'font-size:10px;' : ''}>${label}</div>` : null}
    </div>`;
}

function InventoryCard({ snapshot, time, selectedPlayer }) {
    const cardRef = useRef(null);
    const point = useMemo(() => findPoint(snapshot, selectedPlayer, time), [snapshot, selectedPlayer, time]);
    const participant = playerById(snapshot, selectedPlayer);
    const [hoveredItem, setHoveredItem] = useState(null);
    const grid = new Array(36).fill(null);
    const equipment = { helmet: null, chest: null, legs: null, boots: null, offhand: null };
    if (point) {
        (point.inventory || []).forEach(item => {
            if (item.slot >= 0 && item.slot < 36) grid[item.slot] = item;
            if (item.slot === 100) equipment.helmet = item;
            if (item.slot === 101) equipment.chest = item;
            if (item.slot === 102) equipment.legs = item;
            if (item.slot === 103) equipment.boots = item;
            if (item.slot === 150) equipment.offhand = item;
        });
    }

    const showPopover = (event, item, label) => {
        if (!item || !cardRef.current) return;
        const slotRect = event.currentTarget.getBoundingClientRect();
        const cardRect = cardRef.current.getBoundingClientRect();
        const width = 260;
        const lineCount = 2 + Math.max(0, item.enchantments?.length || 0);
        const estimatedHeight = 72 + (lineCount * 16);
        const left = clamp((slotRect.left + (slotRect.width / 2)) - cardRect.left - (width / 2), 8, Math.max(8, cardRect.width - width - 8));
        let top = slotRect.top - cardRect.top - estimatedHeight - 10;
        if (top < 48) {
            top = Math.min(Math.max(48, slotRect.bottom - cardRect.top + 8), Math.max(48, cardRect.height - estimatedHeight - 8));
        }
        setHoveredItem({ item, label, left, top, width });
    };

    return html`<section class="card inventory-card" ref=${cardRef}>
        <div class="card-header">
            <div class="card-title">Inventory snapshot</div>
        </div>
        ${point ? html`<div class="inventory-body" onScroll=${() => setHoveredItem(null)}>
            <div class="inventory-layout compact-layout">
                <div class="equipment-column">
                    <${ItemSlot} item=${equipment.helmet} label="Helmet" smallLabel=${true} playerColorHex=${participant?.colorHex || null} onHover=${showPopover} onLeave=${() => setHoveredItem(null)} />
                    <${ItemSlot} item=${equipment.chest} label="Chest" smallLabel=${true} playerColorHex=${participant?.colorHex || null} onHover=${showPopover} onLeave=${() => setHoveredItem(null)} />
                    <${ItemSlot} item=${equipment.legs} label="Legs" smallLabel=${true} playerColorHex=${participant?.colorHex || null} onHover=${showPopover} onLeave=${() => setHoveredItem(null)} />
                    <${ItemSlot} item=${equipment.boots} label="Boots" smallLabel=${true} playerColorHex=${participant?.colorHex || null} onHover=${showPopover} onLeave=${() => setHoveredItem(null)} />
                </div>
                <div class="inventory-main">
                    <div class="hand-strip">
                        <${ItemSlot} item=${selectedHotbarItem(point)} label=${point.selectedHotbarSlot != null ? `Main · Slot ${point.selectedHotbarSlot + 1}` : 'Main hand'} smallLabel=${true} playerColorHex=${participant?.colorHex || null} onHover=${showPopover} onLeave=${() => setHoveredItem(null)} />
                        <${ItemSlot} item=${equipment.offhand} label="Offhand" smallLabel=${true} playerColorHex=${participant?.colorHex || null} onHover=${showPopover} onLeave=${() => setHoveredItem(null)} />
                    </div>
                    <div class="inv-grid inventory-primary-grid">
                        ${grid.slice(9, 36).map(item => html`<${ItemSlot} item=${item} playerColorHex=${participant?.colorHex || null} onHover=${showPopover} onLeave=${() => setHoveredItem(null)} />`)}
                    </div>
                    <div class="inv-grid inventory-hotbar-grid">
                        ${grid.slice(0, 9).map((item, index) => html`<${ItemSlot} item=${item} hotbar=${true} selected=${index === (point.selectedHotbarSlot || 0)} playerColorHex=${participant?.colorHex || null} onHover=${showPopover} onLeave=${() => setHoveredItem(null)} />`)}
                    </div>
                </div>
            </div>
        </div>` : html`<div class="empty-state">No inventory sample yet at this time.</div>`}
        ${hoveredItem ? html`<div class="inventory-popover" style=${`left:${hoveredItem.left}px; top:${hoveredItem.top}px; width:${hoveredItem.width}px;`}>
            <strong style=${hoveredItem.item.textColorHex ? `color:${hoveredItem.item.textColorHex};` : ''}>${hoveredItem.item.prettyName}</strong>
            <div class="inventory-popover-subtitle">${hoveredItem.label || pretty(hoveredItem.item.rawId)}</div>
            <div class="inventory-popover-meta">${hoveredItem.item.rawId}</div>
            ${hoveredItem.item.amount > 1 ? html`<div class="inventory-popover-meta">Count ${hoveredItem.item.amount}</div>` : null}
            ${hoveredItem.item.enchantments?.length ? html`<div class="inventory-popover-enchants">${hoveredItem.item.enchantments.map(enchant => html`<div>${enchant.prettyName} ${romanNumeral(enchant.level)}</div>`)}</div>` : hoveredItem.item.enchanted ? html`<div class="inventory-popover-meta">Enchanted</div>` : null}
        </div>` : null}
    </section>`;
}

function TimelineWidget({ snapshot, events, time, setTime, selectedWorld, selectedPlayer, selectedEvent, setSelectedEventId, setSelectedPlayer }) {
    const [groupFilter, setGroupFilter] = useState('ALL');
    const [textFilter, setTextFilter] = useState('');
    const listRef = useRef(null);
    const rowRefs = useRef({});
    const filters = ['ALL', 'combat', 'travel', 'progress', 'system', 'chat'];
    const filtered = useMemo(() => events.filter(event => {
        if (selectedWorld !== 'ALL' && event.world && event.world !== selectedWorld) return false;
        if (groupFilter !== 'ALL' && event.group !== groupFilter) return false;
        if (textFilter) {
            const haystack = `${event.title} ${event.description} ${event.playerName || ''} ${event.subjectName || ''}`.toLowerCase();
            if (!haystack.includes(textFilter.toLowerCase())) return false;
        }
        return true;
    }), [events, groupFilter, textFilter, selectedWorld]);
    const detail = nearestEvent(filtered, time) || selectedEvent || nearestEvent(events, time);

    useEffect(() => {
        if (!detail || !listRef.current) return;
        const row = rowRefs.current[detail.id];
        if (!row) return;
        const list = listRef.current;
        const target = Math.max(0, row.offsetTop - ((list.clientHeight - row.offsetHeight) / 2));
        if (Math.abs(list.scrollTop - target) > 6) {
            list.scrollTo({ top: target, behavior: 'auto' });
        }
    }, [detail?.id, filtered.length]);

    return html`<section class="card log-card">
        <div class="card-header">
            <div class="card-title">Unified event log</div>
            <div class="card-subtitle">${filtered.length} events · focus ${playerById(snapshot, selectedPlayer)?.name || 'Unknown'}</div>
        </div>
        <div class="log-shell">
            <div class="log-top">
                <div class="filter-row">
                    ${filters.map(filter => html`<button class=${`filter-pill ${groupFilter === filter ? 'active' : ''}`} onClick=${() => setGroupFilter(filter)}>${pretty(filter)}</button>`)}
                </div>
                <div class="search-row">
                    <input class="text-input" placeholder="Filter the unified log" value=${textFilter} onInput=${event => setTextFilter(event.target.value)} />
                </div>
            </div>
            ${detail ? html`<div class="log-detail">
                <div class="log-detail-head">
                    <span class="event-icon"><${Icon} name=${resolveEventIcon(detail)} kind=${detail.kind} /></span>
                    <div>
                        <div class="log-detail-title" style=${detail.colorHex ? `color:${detail.colorHex};` : ''}>${detail.title}</div>
                        <div class="muted">${detail.description}</div>
                    </div>
                </div>
                <div class="log-detail-meta">
                    <span>${formatTime(detail.offsetMillis)}</span>
                    ${detail.playerName ? html`<span>${detail.playerName}</span>` : null}
                    ${detail.subjectName ? html`<span>${detail.subjectName}</span>` : null}
                    ${detail.world ? html`<span>${pretty(detail.world)}</span>` : null}
                    ${detail.location ? html`<span>${formatCoord(detail.location.x)}, ${formatCoord(detail.location.y)}, ${formatCoord(detail.location.z)}</span>` : null}
                </div>
            </div>` : null}
            <div class="log-list" ref=${listRef}>
                ${filtered.map(event => html`<div
                    ref=${node => { if (node) rowRefs.current[event.id] = node; }}
                    class=${`log-item ${event.offsetMillis > time ? 'future' : ''} ${detail?.id === event.id ? 'active' : ''}`}
                    style=${event.colorHex ? `--row-accent:${event.colorHex};` : ''}
                    onClick=${() => {
                        setTime(event.offsetMillis);
                        setSelectedEventId(event.id);
                        if (event.playerUuid) setSelectedPlayer(event.playerUuid);
                    }}>
                    <span class="log-time">${formatTime(event.offsetMillis)}</span>
                    <span class="event-icon"><${Icon} name=${resolveEventIcon(event)} kind=${event.kind} /></span>
                    <span>
                        <span class="log-title" style=${event.colorHex ? `color:${event.colorHex};` : ''}>${event.title}</span>
                        <span class="log-desc">${event.description}</span>
                    </span>
                </div>`)}
            </div>
        </div>
    </section>`;
}

function resolveEventIcon(event) {
    if (!event) return null;
    if (event.kind === 'gamemode') return gamemodeIconToken(event.rawName);
    if (event.kind === 'damage' || event.kind === 'environment') {
        return event.iconToken || entityIconToken(event.subjectName) || KIND_ICON[event.kind];
    }
    if (event.kind === 'death') {
        return event.iconToken || 'minecraft:wither_skeleton_skull';
    }
    if (event.kind === 'projectile') return event.iconToken || 'minecraft:arrow';
    if (event.kind === 'portal') return 'minecraft:obsidian';
    if (event.kind === 'spawn' || event.kind === 'world_spawn') return 'minecraft:red_bed';
    if (event.kind === 'travel' || event.kind === 'jump') return event.iconToken || 'minecraft:ender_pearl';
    if (['advancement', 'challenge', 'goal', 'milestone'].includes(event.kind)) return event.iconToken || KIND_ICON[event.kind] || 'minecraft:nether_star';
    if (event.kind === 'food') return event.iconToken || 'minecraft:cooked_beef';
    if (event.kind === 'effect') return event.iconToken || 'minecraft:potion';
    if (event.kind === 'shield') return 'minecraft:shield';
    if (event.kind === 'dragon') return 'minecraft:dragon_head';
    if (event.kind === 'crystal') return 'minecraft:end_crystal';
    if (event.rawName && event.rawName.toUpperCase() === event.rawName) return gamemodeIconToken(event.rawName);
    return event.iconToken || KIND_ICON[event.kind] || event.kind;
}

render(html`<${App} />`, document.getElementById('app'));
