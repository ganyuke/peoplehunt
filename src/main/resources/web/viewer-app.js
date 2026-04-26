const { h, render } = window.preact;
const { useState, useEffect, useMemo, useRef } = window.preactHooks;
const html = window.htm.bind(h);

const snapshot = window.PEOPLEHUNT_SNAPSHOT;

function formatTime(ms) {
    if (ms < 0) ms = 0;
    const totalSec = Math.floor(ms / 1000);
    const min = Math.floor(totalSec / 60);
    const sec = totalSec % 60;
    return `${min}:${sec.toString().padStart(2, '0')}`;
}

function App() {
    if (!snapshot) return html`<div class="bento-container"><div class="bento-card" style="padding: 20px;">No report data available.</div></div>`;

    const duration = Math.max(0, snapshot.metadata.endedAtEpochMillis - snapshot.metadata.startedAtEpochMillis);
    const [time, setTime] = useState(0);
    const [playing, setPlaying] = useState(false);
    const [speed, setSpeed] = useState(10);
    const [selectedPlayer, setSelectedPlayer] = useState(
        snapshot.metadata.runnerUuid || (snapshot.participants[0] ? snapshot.participants[0].uuid : null)
    );

    const lastFrameTime = useRef(performance.now());

    useEffect(() => {
        if (!playing) return;
        let req;
        const loop = (now) => {
            const delta = now - lastFrameTime.current;
            lastFrameTime.current = now;
            setTime(t => {
                const next = t + delta * speed;
                if (next >= duration) {
                    setPlaying(false);
                    return duration;
                }
                return next;
            });
            req = requestAnimationFrame(loop);
        };
        lastFrameTime.current = performance.now();
        req = requestAnimationFrame(loop);
        return () => cancelAnimationFrame(req);
    }, [playing, speed, duration]);

    return html`
        <div class="bento-container">
            <${MetaRow} meta=${snapshot.metadata} duration=${duration} />
            
            <${MapWidget} time=${time} snapshot=${snapshot} />
            <${StatusWidget} time=${time} selectedPlayer=${selectedPlayer} setSelectedPlayer=${setSelectedPlayer} snapshot=${snapshot} />
            
            <${PlaybackWidget} time=${time} duration=${duration} onSeek=${setTime} playing=${playing} setPlaying=${setPlaying} speed=${speed} setSpeed=${setSpeed} snapshot=${snapshot} />
            <${LogWidget} time=${time} setTime=${setTime} snapshot=${snapshot} />
        </div>
    `;
}

function MetaRow({ meta, duration }) {
    return html`
        <div class="meta-row">
            <div class="bento-card meta-widget">
                <span class="meta-label">Runner</span>
                <span class="meta-val">${meta.runnerName}</span>
            </div>
            <div class="bento-card meta-widget">
                <span class="meta-label">Outcome</span>
                <span class="meta-val">${meta.outcome.replace(/_/g, ' ')}</span>
            </div>
            <div class="bento-card meta-widget">
                <span class="meta-label">Match Duration</span>
                <span class="meta-val">${formatTime(duration)}</span>
            </div>
            <div class="bento-card meta-widget">
                <span class="meta-label">Inventory Control</span>
                <span class="meta-val" style="text-transform: capitalize;">${meta.keepInventoryMode.toLowerCase()}</span>
            </div>
        </div>
    `;
}

function MapWidget({ time, snapshot }) {
    const canvasRef = useRef(null);

    const bounds = useMemo(() => {
        let minX = Infinity, minZ = Infinity, maxX = -Infinity, maxZ = -Infinity;
        snapshot.paths.forEach(p => {
            if (p.x < minX) minX = p.x;
            if (p.x > maxX) maxX = p.x;
            if (p.z < minZ) minZ = p.z;
            if (p.z > maxZ) maxZ = p.z;
        });
        if (minX === Infinity) return { minX: -100, maxX: 100, minZ: -100, maxZ: 100 };
        const pad = 80;
        return { minX: minX - pad, maxX: maxX + pad, minZ: minZ - pad, maxZ: maxZ + pad };
    }, [snapshot]);

    useEffect(() => {
        const canvas = canvasRef.current;
        const ctx = canvas.getContext('2d');
        const w = canvas.width;
        const h = canvas.height;
        ctx.clearRect(0, 0, w, h);

        const scaleX = w / (bounds.maxX - bounds.minX);
        const scaleZ = h / (bounds.maxZ - bounds.minZ);
        const scale = Math.min(scaleX, scaleZ);
        const cx = (bounds.maxX + bounds.minX) / 2;
        const cz = (bounds.maxZ + bounds.minZ) / 2;

        const toCanvas = (x, z) => ({
            x: w/2 + (x - cx) * scale,
            y: h/2 + (z - cz) * scale
        });

        // Optional: Draw a subtle grid
        ctx.strokeStyle = '#e5e7eb';
        ctx.lineWidth = 1;
        ctx.beginPath();
        for(let i = 0; i < w; i += 50) { ctx.moveTo(i, 0); ctx.lineTo(i, h); }
        for(let j = 0; j < h; j += 50) { ctx.moveTo(0, j); ctx.lineTo(w, j); }
        ctx.stroke();

        const pathsByPlayer = {};
        snapshot.paths.forEach(p => {
            if (p.offsetMillis <= time) {
                if (!pathsByPlayer[p.playerUuid]) pathsByPlayer[p.playerUuid] =[];
                pathsByPlayer[p.playerUuid].push(p);
            }
        });

        const getPlayerColor = (uuid) => {
            const p = snapshot.participants.find(p => p.uuid === uuid);
            return p ? p.colorHex : '#9ca3af';
        };

        for (const uuid in pathsByPlayer) {
            const pts = pathsByPlayer[uuid];
            if (pts.length === 0) continue;

            ctx.strokeStyle = getPlayerColor(uuid);
            ctx.lineWidth = 3;
            ctx.lineJoin = 'round';
            ctx.lineCap = 'round';

            ctx.beginPath();
            let lastPt = null;

            pts.forEach(p => {
                const c = toCanvas(p.x, p.z);
                if (!lastPt || p.isTeleport || p.world !== lastPt.world) {
                    ctx.moveTo(c.x, c.y);
                } else {
                    ctx.lineTo(c.x, c.y);
                }
                lastPt = p;
            });
            ctx.stroke();

            // Head marker
            if (lastPt) {
                const c = toCanvas(lastPt.x, lastPt.z);
                ctx.beginPath();
                ctx.fillStyle = ctx.strokeStyle;
                ctx.arc(c.x, c.y, 5, 0, Math.PI*2);
                ctx.fill();
                ctx.lineWidth = 2;
                ctx.strokeStyle = '#ffffff';
                ctx.stroke();
            }
        }

        // Deaths
        snapshot.deaths.forEach(d => {
            if (d.offsetMillis <= time) {
                const c = toCanvas(d.location.x, d.location.z);
                ctx.fillStyle = 'var(--danger)';
                ctx.font = 'bold 16px sans-serif';
                ctx.textAlign = 'center';
                ctx.textBaseline = 'middle';
                ctx.fillText('✖', c.x, c.y);
            }
        });

    },[time, bounds, snapshot]);

    useEffect(() => {
        const handleResize = () => {
            if (canvasRef.current) {
                const parent = canvasRef.current.parentElement;
                canvasRef.current.width = parent.clientWidth;
                canvasRef.current.height = parent.clientHeight;
            }
        };
        window.addEventListener('resize', handleResize);
        handleResize();
        return () => window.removeEventListener('resize', handleResize);
    },[]);

    return html`
        <div class="bento-card map-widget">
            <div class="card-header">Geospatial Track</div>
            <div class="map-canvas-container">
                <canvas ref=${canvasRef}></canvas>
            </div>
        </div>
    `;
}

function PlaybackWidget({ time, duration, onSeek, playing, setPlaying, speed, setSpeed, snapshot }) {
    const playIcon = playing ? '⏸' : '▶';
    
    return html`
        <div class="bento-card playback-widget">
            <button class="play-btn" onClick=${() => setPlaying(!playing)} title="Play/Pause">
                ${playIcon}
            </button>
            
            <div class="track-wrapper">
                <div class="track-bg"></div>
                <div class="track-fill" style="width: ${(time / duration) * 100}%"></div>
                
                ${snapshot.deaths.map(d => html`
                    <div class="track-marker" style="background: var(--danger); left: ${(d.offsetMillis / duration) * 100}%" title="Death: ${d.victimName}"></div>
                `)}
                ${snapshot.totems.map(t => html`
                    <div class="track-marker" style="background: var(--totem); left: ${(t.offsetMillis / duration) * 100}%" title="Totem Pop: ${t.playerName}"></div>
                `)}
                
                <input type="range" min="0" max=${duration} value=${time} onInput=${e => { setPlaying(false); onSeek(Number(e.target.value)); }} />
            </div>
            
            <div style="font-variant-numeric: tabular-nums; font-weight: 600; color: var(--text-main); font-size: 14px;">
                ${formatTime(time)} <span style="color: var(--text-muted); font-weight: 500;">/ ${formatTime(duration)}</span>
            </div>
            
            <select class="speed-select" value=${speed} onChange=${e => setSpeed(Number(e.target.value))}>
                <option value="1">1x Speed</option>
                <option value="5">5x Speed</option>
                <option value="10">10x Speed</option>
                <option value="30">30x Speed</option>
                <option value="60">60x Speed</option>
            </select>
        </div>
    `;
}

function StatusWidget({ time, selectedPlayer, setSelectedPlayer, snapshot }) {
    const point = useMemo(() => {
        let last = null;
        for (let i = 0; i < snapshot.paths.length; i++) {
            const p = snapshot.paths[i];
            if (p.playerUuid === selectedPlayer && p.offsetMillis <= time) last = p;
        }
        return last;
    }, [time, selectedPlayer, snapshot.paths]);

    const grid = new Array(36).fill(null);
    let equipment = { helmet: null, chest: null, legs: null, boots: null, offhand: null };
    
    if (point) {
        point.inventory.forEach(item => {
            if (item.slot >= 0 && item.slot < 36) grid[item.slot] = item;
            if (item.slot === 103) equipment.helmet = item;
            if (item.slot === 102) equipment.chest = item;
            if (item.slot === 101) equipment.legs = item;
            if (item.slot === 100) equipment.boots = item;
            if (item.slot === 150) equipment.offhand = item;
        });
    }

    const renderSlot = (item) => html`
        <div class="inv-slot ${item ? 'has-item' : ''}" title=${item ? item.prettyName : ''}>
            ${item ? html`
                <span style="color: ${item.textColorHex || '#111827'}">■</span>
                ${item.amount > 1 ? html`<span class="inv-count">${item.amount}</span>` : ''}
            ` : '·'}
        </div>
    `;

    const healthPct = point ? Math.min(100, Math.max(0, (point.health / point.maxHealth) * 100)) : 0;
    const foodPct = point ? Math.min(100, Math.max(0, (point.food / 20) * 100)) : 0;

    return html`
        <div class="bento-card status-widget">
            <div class="card-header">Target Status</div>
            
            <div class="segmented-control">
                ${snapshot.participants.map(p => html`
                    <div class="segment ${p.uuid === selectedPlayer ? 'active' : ''}" onClick=${() => setSelectedPlayer(p.uuid)}>
                        ${p.name}
                    </div>
                `)}
            </div>

            ${point ? html`
                <div class="stat-bars">
                    <div class="bar-group">
                        <div class="bar-labels"><span>Health</span> <span>${point.health.toFixed(1)} / ${point.maxHealth}</span></div>
                        <div class="bar-track"><div class="bar-fill" style="width: ${healthPct}%; background: var(--danger);"></div></div>
                    </div>
                    <div class="bar-group">
                        <div class="bar-labels"><span>Food & Saturation</span> <span>${point.food} (${point.saturation.toFixed(1)})</span></div>
                        <div class="bar-track"><div class="bar-fill" style="width: ${foodPct}%; background: var(--food);"></div></div>
                    </div>
                    <div class="bar-group" style="margin-top: 4px;">
                        <div class="bar-labels"><span>XP Level</span> <span style="color: var(--xp); font-weight: 700;">${point.xpLevel}</span></div>
                        <div style="font-size: 12px; color: var(--text-muted);">
                            Location: ${point.world.replace('minecraft:', '')} ${Math.round(point.x)}, ${Math.round(point.y)}, ${Math.round(point.z)}
                        </div>
                    </div>
                </div>

                <div class="inventory-section">
                    <div style="display: flex; justify-content: space-between;">
                        <div style="display: flex; gap: 4px;">
                            ${renderSlot(equipment.helmet)}
                            ${renderSlot(equipment.chest)}
                            ${renderSlot(equipment.legs)}
                            ${renderSlot(equipment.boots)}
                        </div>
                        <div>${renderSlot(equipment.offhand)}</div>
                    </div>
                    <div class="inv-grid">
                        ${grid.slice(9, 36).map(item => renderSlot(item))}
                    </div>
                    <div class="inv-grid" style="margin-top: 4px;">
                        ${grid.slice(0, 9).map(item => renderSlot(item))}
                    </div>
                </div>
            ` : html`
                <div style="flex: 1; display: flex; align-items: center; justify-content: center; color: var(--text-muted); font-size: 13px;">
                    Awaiting drop data...
                </div>
            `}
        </div>
    `;
}

function LogWidget({ time, setTime, snapshot }) {
    const[filter, setFilter] = useState('ALL');

    const events = useMemo(() => {
        return[
            ...snapshot.timeline.map(t => ({ ...t, _type: 'timeline' })),
            ...snapshot.chat.map(c => ({
                offsetMillis: c.offsetMillis,
                playerUuid: c.playerUuid,
                playerName: c.playerName,
                kind: 'chat',
                description: c.plainText,
                colorHex: '#9ca3af',
                _type: 'chat'
            }))
        ].sort((a, b) => a.offsetMillis - b.offsetMillis);
    }, [snapshot]);

    const filtered = useMemo(() => {
        if (filter === 'ALL') return events;
        return events.filter(e => e.kind === filter || (filter === 'chat' && e._type === 'chat'));
    }, [events, filter]);

    const filters =['ALL', 'chat', 'combat', 'death', 'milestone', 'effect', 'food', 'spawn'];

    return html`
        <div class="bento-card log-widget">
            <div class="card-header">Activity Feed</div>
            <div class="log-filters">
                ${filters.map(f => html`
                    <div class="filter-chip ${f === filter ? 'active' : ''}" onClick=${() => setFilter(f)}>
                        ${f.charAt(0).toUpperCase() + f.slice(1)}
                    </div>
                `)}
            </div>
            <div class="log-list">
                ${filtered.map(e => html`
                    <div class="log-item" onClick=${() => setTime(e.offsetMillis)} style=${e.offsetMillis > time ? 'opacity: 0.4' : ''}>
                        <div class="log-time">${formatTime(e.offsetMillis)}</div>
                        <div class="log-dot" style="background: ${e.colorHex || '#d1d5db'}"></div>
                        <div class="log-msg">
                            <strong>${e.playerName}</strong> <span>${e.description}</span>
                        </div>
                    </div>
                `)}
            </div>
        </div>
    `;
}

render(html`<${App} />`, document.getElementById('app'));