(() => {
  const { h, render } = preact;
  const { useEffect, useMemo, useRef, useState } = preactHooks;
  const html = htm.bind(h);

  function PeopleHuntApp() {
    const [snapshot, setSnapshot] = useState(null);
    const [selectedWorld, setSelectedWorld] = useState(null);
    const [visiblePlayers, setVisiblePlayers] = useState([]);
    const [selectedPlayerUuid, setSelectedPlayerUuid] = useState(null);
    const [hoveredPlayerUuid, setHoveredPlayerUuid] = useState(null);
    const [currentTime, setCurrentTime] = useState(0);
    const [activeTimelineKinds, setActiveTimelineKinds] = useState([]);
    const [toggles, setToggles] = useState({
      projectiles: true,
      jumps: true,
      spectator: true,
      threats: true,
      spawns: true,
      end: true,
      absorptionSeparate: false
    });
    const mapCanvasRef = useRef(null);
    const healthCanvasRef = useRef(null);
    const markerHitsRef = useRef([]);

    useEffect(() => {
      let cancelled = false;
      (async () => {
        try {
          const inline = window.PEOPLEHUNT_SNAPSHOT;
          if (!inline || typeof inline !== 'object') {
            throw new Error('Missing embedded report snapshot.');
          }
          const loaded = inline;
          if (cancelled) return;
          const participants = loaded.participants || [];
          const worlds = uniqueWorlds(loaded);
          const kinds = uniqueTimelineKinds(loaded);
          setSnapshot(loaded);
          setSelectedWorld(preferredWorld(loaded));
          setVisiblePlayers(participants.map(participant => participant.uuid));
          setSelectedPlayerUuid(loaded.metadata ? loaded.metadata.runnerUuid : (participants[0] ? participants[0].uuid : null));
          setCurrentTime(maxOffset(loaded));
          setActiveTimelineKinds(kinds);
          primeAvatarCache(participants).catch(() => {});
          if (worlds.length === 1 && !worlds[0]) setSelectedWorld('minecraft:overworld');
        } catch (error) {
          console.error(error);
          setSnapshot({ error: String(error) });
        }
      })();
      return () => {
        cancelled = true;
      };
    }, []);

    const visiblePlayersSet = useMemo(() => new Set(visiblePlayers), [visiblePlayers]);
    const activeKindsSet = useMemo(() => new Set(activeTimelineKinds), [activeTimelineKinds]);
    const dimensions = useMemo(() => snapshot ? uniqueWorlds(snapshot) : [], [snapshot]);
    const timelineKinds = useMemo(() => snapshot ? uniqueTimelineKinds(snapshot) : [], [snapshot]);
    const groupedParticipants = useMemo(() => groupParticipants(snapshot), [snapshot]);
    const effectiveSelectedPlayerUuid = hoveredPlayerUuid || selectedPlayerUuid;
    const selectedPlayer = useMemo(() => participantById(snapshot, effectiveSelectedPlayerUuid), [snapshot, effectiveSelectedPlayerUuid]);
    const statusPoint = useMemo(() => latestPoint(snapshot, effectiveSelectedPlayerUuid, currentTime), [snapshot, effectiveSelectedPlayerUuid, currentTime]);
    const timelineRows = useMemo(() => buildTimelineRows(snapshot, currentTime, activeKindsSet, effectiveSelectedPlayerUuid), [snapshot, currentTime, activeKindsSet, effectiveSelectedPlayerUuid]);
    const chatRows = useMemo(() => buildChatRows(snapshot, effectiveSelectedPlayerUuid), [snapshot, effectiveSelectedPlayerUuid]);
    const summaryText = useMemo(() => buildSummary(snapshot), [snapshot]);

    useEffect(() => {
      if (!snapshot || snapshot.error) return;
      drawMap({
        snapshot,
        canvas: mapCanvasRef.current,
        selectedWorld,
        visiblePlayersSet,
        toggles,
        currentTime,
        effectiveSelectedPlayerUuid,
        markerHitsRef,
        setHoveredPlayerUuid
      });
    }, [snapshot, selectedWorld, visiblePlayersSet, toggles, currentTime, effectiveSelectedPlayerUuid]);

    useEffect(() => {
      if (!snapshot || snapshot.error) return;
      drawHealth({ snapshot, canvas: healthCanvasRef.current, visiblePlayersSet, toggles, currentTime, selectedPlayerUuid: effectiveSelectedPlayerUuid });
    }, [snapshot, visiblePlayersSet, toggles, currentTime, effectiveSelectedPlayerUuid]);

    if (!snapshot) {
      return html`<div class="page"><div class="panel">Loading report...</div></div>`;
    }
    if (snapshot.error) {
      return html`<div class="page"><div class="panel">Failed to load report.</div></div>`;
    }

    return html`
      <div class="page app-root">
        <header class="header panel">
          <div>
            <h1>PeopleHunt After Action Report</h1>
            <div class="summary">${summaryText}</div>
          </div>
          <div class="header-meta">
            <div class="selection-label">${selectionLabel(snapshot, selectedPlayer, hoveredPlayerUuid)}</div>
            <div class="legend-muted">Hover a map marker for transient status. Click a player chip to pin the status panel.</div>
          </div>
        </header>

        <section class="panel controls">
          <div class="scrubber-row">
            <label for="scrubber">Time</label>
            <input
              id="scrubber"
              type="range"
              min="0"
              max="${maxOffset(snapshot)}"
              value="${currentTime}"
              step="1000"
              onInput=${event => setCurrentTime(Number(event.currentTarget.value || 0))}
            >
            <span>${formatDuration(currentTime)}</span>
          </div>
          <div class="tabs">
            ${dimensions.map(world => html`
              <button
                type="button"
                class=${selectedWorld === world ? 'tab active' : 'tab'}
                onClick=${() => setSelectedWorld(world)}
              >${shortenWorld(world)}</button>
            `)}
          </div>
          <div class="toggle-row">
            ${toggleSpecs().map(spec => html`
              <button
                type="button"
                class=${toggles[spec.key] ? 'toggle active' : 'toggle inactive'}
                onClick=${() => setToggles(previous => ({ ...previous, [spec.key]: !previous[spec.key] }))}
              >${spec.label}</button>
            `)}
          </div>
          <div class="player-groups">
            ${['RUNNER', 'HUNTERS', 'SPECTATORS'].map(group => html`
              <div class="player-group">
                <h3>${group}</h3>
                <div class="player-list">
                  ${(groupedParticipants[group] || []).map(player => html`
                    <button
                      type="button"
                      class=${playerChipClass(player, visiblePlayersSet, effectiveSelectedPlayerUuid)}
                      onClick=${() => {
                        setSelectedPlayerUuid(player.uuid);
                        setHoveredPlayerUuid(null);
                        setVisiblePlayers(toggleVisiblePlayer(visiblePlayers, player.uuid));
                      }}
                    >
                      <${Avatar} uuid=${player.uuid} name=${player.name} small=${true} />
                      <span class="swatch" style=${`background:${player.colorHex}`}></span>
                      ${player.name}
                    </button>
                  `)}
                </div>
              </div>
            `)}
          </div>
        </section>

        <section class="layout">
          <div class="left-column">
            <div class="panel">
              <div class="panel-title-row">
                <h2>Map</h2>
                <div class="legend-muted">Solid = active path · dashed = jump/spectator · dotted = markers/threats</div>
              </div>
              <canvas
                ref=${mapCanvasRef}
                width="1280"
                height="700"
                onMouseMove=${event => handleMapMove(event, mapCanvasRef.current, markerHitsRef.current, setHoveredPlayerUuid)}
                onMouseLeave=${() => setHoveredPlayerUuid(null)}
              ></canvas>
            </div>
            <div class="panel">
              <div class="panel-title-row">
                <h2>Health over time</h2>
                <div class="legend-muted">Deaths and totems are marked as vertical events.</div>
              </div>
              <canvas ref=${healthCanvasRef} width="1280" height="260"></canvas>
            </div>
          </div>

          <div class="right-column">
            <div class="panel">
              <div class="panel-title-row">
                <h2>Status at current time</h2>
                <div class="legend-muted">${formatDuration(currentTime)}</div>
              </div>
              <div class="status-panel">
                ${statusPoint && selectedPlayer
                  ? html`<${StatusCard} snapshot=${snapshot} player=${selectedPlayer} point=${statusPoint} currentTime=${currentTime} toggles=${toggles} />`
                  : html`<div class="empty">Select or hover a participant to inspect their state at the current time.</div>`}
              </div>
            </div>

            <div class="panel">
              <div class="panel-title-row">
                <h2>Unified event feed</h2>
                <div class="legend-muted">Dense chronological log near the scrubber.</div>
              </div>
              <div class="timeline-filters">
                ${timelineKinds.map(kind => html`
                  <button
                    type="button"
                    class=${activeKindsSet.has(kind) ? 'filter-chip active' : 'filter-chip inactive'}
                    onClick=${() => setActiveTimelineKinds(toggleTimelineKind(activeTimelineKinds, kind))}
                  >${kind}</button>
                `)}
              </div>
              <div class="scroll-box timeline-box">
                ${timelineRows.length
                  ? timelineRows.map(entry => html`<${TimelineRow} entry=${entry} />`)
                  : html`<div class="empty">No timeline entries near this moment.</div>`}
              </div>
            </div>

            <div class="panel">
              <div class="panel-title-row">
                <h2>Chat log</h2>
                <div class="legend-muted">Public chat plus captured whispers.</div>
              </div>
              <div class="scroll-box chat-log">
                ${chatRows.length
                  ? chatRows.map(entry => html`<${ChatRow} entry=${entry} />`)
                  : html`<div class="empty">No chat captured for this report.</div>`}
              </div>
            </div>
          </div>
        </section>
      </div>
    `;
  }

  function StatusCard({ snapshot, player, point, currentTime, toggles }) {
    const deaths = (snapshot.deaths || []).filter(entry => entry.victimUuid === player.uuid && entry.offsetMillis <= currentTime).length;
    const totalHealth = Math.max(0, Number(point.health || 0) + Number(point.absorption || 0));
    const recentFood = recentPlayerEntries(snapshot.food || [], player.uuid, currentTime, 3);
    const recentEffects = recentPlayerEntries(snapshot.effects || [], player.uuid, currentTime, 3);
    const recentTotems = recentPlayerEntries(snapshot.totems || [], player.uuid, currentTime, 2);
    const recentBlocks = recentPlayerEntries(snapshot.blocks || [], player.uuid, currentTime, 3);
    return html`
      <div class="status-card">
        <div class="status-head">
          <div>
            <div class="status-name"><${Avatar} uuid=${player.uuid} name=${player.name} /> <span class="swatch" style=${`background:${player.colorHex}`}></span>${player.name}</div>
            <div class="status-role">${player.role} · ${point.gameMode || 'UNKNOWN'} · life ${point.lifeIndex}</div>
          </div>
          <div class="legend-muted">${shortenWorld(point.world)}</div>
        </div>
        <div class="status-grid">
          <div class="status-field">
            <span class="label">Health</span>
            <span class="hearts">${renderHearts(Number(point.health || 0), Number(point.maxHealth || 20), Number(point.absorption || 0), toggles.absorptionSeparate)}</span>
            <div>${toggles.absorptionSeparate ? `${Number(point.health || 0).toFixed(1)} hp + ${Number(point.absorption || 0).toFixed(1)} absorption` : `${totalHealth.toFixed(1)} / ${Number(point.maxHealth || 20).toFixed(1)}`}</div>
          </div>
          <div class="status-field">
            <span class="label">Hunger</span>
            <span class="foodbar">${renderFood(point.food || 0)}</span>
            <div>${String(point.food || 0)} food · ${Number(point.saturation || 0).toFixed(1)} saturation</div>
          </div>
          <div class="status-field">
            <span class="label">Coordinates</span>
            <div>${point.x.toFixed(1)}, ${point.y.toFixed(1)}, ${point.z.toFixed(1)}</div>
          </div>
          <div class="status-field">
            <span class="label">XP / deaths</span>
            <div>${String(point.xpLevel || 0)} levels · ${String(point.totalExperience || 0)} xp · ${String(deaths)} deaths</div>
          </div>
        </div>
        ${(point.effects || []).length ? html`
          <div class="effects-list">
            ${(point.effects || []).map(effect => html`
              <span class="effect-pill" title=${effect.rawType || ''}>${effect.prettyName} ${roman((effect.amplifier || 0) + 1)}</span>
            `)}
          </div>
        ` : null}
        ${(recentFood.length || recentEffects.length || recentTotems.length || recentBlocks.length) ? html`
          <div class="status-events-grid">
            ${recentFood.length ? html`
              <div class="status-events-card">
                <div class="label">Recent food</div>
                ${recentFood.map(entry => html`<div class="status-event-row"><span style=${`color:${entry.colorHex || '#ffffff'}`}>${entry.prettyName}</span><span class="legend-muted">${formatDuration(entry.offsetMillis)}</span></div>`)}
              </div>
            ` : null}
            ${recentEffects.length ? html`
              <div class="status-events-card">
                <div class="label">Recent effects</div>
                ${recentEffects.map(entry => html`<div class="status-event-row"><span>${entry.action === 'REMOVED' || entry.action === 'CLEARED' ? 'Lost' : entry.action === 'CHANGED' ? 'Changed' : 'Gained'} ${entry.prettyName}${entry.amplifier != null ? ` ${roman((entry.amplifier || 0) + 1)}` : ''}</span><span class="legend-muted">${formatDuration(entry.offsetMillis)}</span></div>`)}
              </div>
            ` : null}
            ${recentTotems.length ? html`
              <div class="status-events-card">
                <div class="label">Recent totems</div>
                ${recentTotems.map(entry => html`<div class="status-event-row"><span style=${`color:${entry.colorHex || '#ffff55'}`}>Totem popped</span><span class="legend-muted">${formatDuration(entry.offsetMillis)}</span></div>`)}
              </div>
            ` : null}
            ${recentBlocks.length ? html`
              <div class="status-events-card">
                <div class="label">Recent blocks</div>
                ${recentBlocks.map(entry => html`<div class="status-event-row"><span>Blocked ${Number(entry.blockedDamage || 0).toFixed(1)} from ${entry.attackerName || 'unknown'}</span><span class="legend-muted">${formatDuration(entry.offsetMillis)}</span></div>`)}
              </div>
            ` : null}
          </div>
        ` : null}
        ${(point.inventory || []).length ? html`
          <div class="inventory-grid">
            ${(point.inventory || []).map(item => html`
              <div class="inventory-item">
                <span class="slot">${slotName(item.slot)}</span>
                <div style=${`color:${item.textColorHex || '#ffffff'}`} title=${item.rawId || ''}>${item.prettyName} ×${item.amount}</div>
              </div>
            `)}
          </div>
        ` : null}
      </div>
    `;
  }

  function TimelineRow({ entry }) {
    const style = entry.colorHex ? { color: entry.colorHex } : undefined;
    return html`
      <div class=${`timeline-row ${escapeClass(entry.kind)}`}>
        <div class="timeline-time">${formatDuration(entry.offsetMillis)}</div>
        <div class="timeline-kind">${entry.kind}</div>
        <div class="timeline-body">
          <strong>${entry.playerName || 'system'}</strong>
          ${' — '}
          <span class=${entry.rawName ? 'raw-name' : ''} style=${style} title=${entry.rawName || ''}>${entry.description}</span>
          ${entry.detail ? html`<div class="timeline-detail">${entry.detail}</div>` : null}
        </div>
      </div>
    `;
  }

  function ChatRow({ entry }) {
    return html`
      <div class="chat-row">
        <div class="chat-time">${formatDuration(entry.offsetMillis)}</div>
        <div class="timeline-kind">${entry.kind}</div>
        <div class="chat-body" dangerouslySetInnerHTML=${{ __html: entry.html || escapeHtml(entry.plainText || '') }}></div>
      </div>
    `;
  }

  function Avatar({ uuid, name, small }) {
    const [failed, setFailed] = useState(false);
    const src = failed ? svgAvatar(name, uuid) : cachedAvatarUrl(uuid);
    return html`<img class=${small ? 'avatar' : 'avatar'} src=${src} alt="" loading="lazy" referrerPolicy="no-referrer" onError=${() => setFailed(true)} />`;
  }

  function handleMapMove(event, canvas, hits, setHoveredPlayerUuid) {
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const x = (event.clientX - rect.left) * (canvas.width / rect.width);
    const y = (event.clientY - rect.top) * (canvas.height / rect.height);
    const hit = (hits || []).find(marker => Math.hypot(marker.x - x, marker.y - y) <= marker.r);
    setHoveredPlayerUuid(hit ? hit.uuid : null);
  }

  function drawMap(context) {
    const { snapshot, canvas, selectedWorld, visiblePlayersSet, toggles, currentTime, effectiveSelectedPlayerUuid, markerHitsRef } = context;
    if (!canvas || !snapshot) return;
    const mapCtx = canvas.getContext('2d');
    clearCanvas(mapCtx, canvas);
    const paths = (snapshot.paths || []).filter(point => point.world === selectedWorld && point.offsetMillis <= currentTime && visiblePlayersSet.has(point.playerUuid));
    const markers = (snapshot.markers || []).filter(marker => marker.world === selectedWorld && marker.offsetMillis <= currentTime && (!marker.endedAtOffsetMillis || marker.endedAtOffsetMillis > currentTime));
    const projectilePts = projectilePoints(snapshot, currentTime, selectedWorld);
    const mobPts = mobPoints(snapshot, currentTime, selectedWorld);
    const dragonPts = dragonPoints(snapshot, currentTime, selectedWorld);
    const crystals = crystalPoints(snapshot, currentTime, selectedWorld);
    const bounds = computeBounds(paths, markers, projectilePts, mobPts, dragonPts, crystals);
    drawMapGrid(mapCtx, canvas, bounds);
    markerHitsRef.current = [];
    drawPaths(mapCtx, canvas, snapshot, bounds, paths, visiblePlayersSet, toggles, currentTime, effectiveSelectedPlayerUuid, markerHitsRef.current);
    if (toggles.projectiles) drawProjectiles(mapCtx, canvas, snapshot, bounds, selectedWorld, currentTime, visiblePlayersSet);
    if (toggles.threats) drawMobs(mapCtx, canvas, snapshot, bounds, selectedWorld, currentTime);
    if (toggles.spawns || toggles.jumps) drawMarkers(mapCtx, canvas, snapshot, bounds, selectedWorld, currentTime, toggles, effectiveSelectedPlayerUuid);
    if (toggles.end) drawEndEntities(mapCtx, canvas, snapshot, bounds, selectedWorld, currentTime);
  }

  function drawHealth({ snapshot, canvas, visiblePlayersSet, toggles, currentTime, selectedPlayerUuid }) {
    if (!canvas || !snapshot) return;
    const ctx = canvas.getContext('2d');
    clearCanvas(ctx, canvas);
    const filtered = (snapshot.paths || []).filter(point => point.offsetMillis <= currentTime && visiblePlayersSet.has(point.playerUuid));
    if (!filtered.length) {
      drawEmpty(ctx, canvas, 'No visible health samples.');
      return;
    }
    const duration = Math.max(1, maxOffset(snapshot));
    const maxHealth = Math.max(20, ...filtered.map(point => Number(point.maxHealth) || 20), ...filtered.map(point => (Number(point.health) || 0) + (toggles.absorptionSeparate ? 0 : Number(point.absorption) || 0)));
    ctx.strokeStyle = '#334454';
    ctx.lineWidth = 1;
    for (let i = 0; i <= maxHealth; i += 5) {
      const y = graphY(i, maxHealth, canvas);
      ctx.beginPath();
      ctx.moveTo(40, y);
      ctx.lineTo(canvas.width - 12, y);
      ctx.stroke();
      ctx.fillStyle = '#9fb0c0';
      ctx.fillText(String(i), 10, y + 4);
    }
    (snapshot.deaths || []).filter(entry => entry.offsetMillis <= currentTime && visiblePlayersSet.has(entry.victimUuid)).forEach(entry => {
      const x = 40 + (entry.offsetMillis / duration) * (canvas.width - 60);
      ctx.fillStyle = 'rgba(255, 107, 107, 0.14)';
      ctx.fillRect(x - 2, 18, 4, canvas.height - 36);
    });
    (snapshot.totems || []).filter(entry => entry.offsetMillis <= currentTime && visiblePlayersSet.has(entry.playerUuid)).forEach(entry => {
      const x = 40 + (entry.offsetMillis / duration) * (canvas.width - 60);
      ctx.strokeStyle = 'rgba(251, 191, 36, 0.85)';
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.moveTo(x, 18);
      ctx.lineTo(x, canvas.height - 18);
      ctx.stroke();
      ctx.fillStyle = '#fbbf24';
      ctx.beginPath();
      ctx.arc(x, 16, 3, 0, Math.PI * 2);
      ctx.fill();
    });
    groupBy(filtered, point => `${point.playerUuid}:${point.lifeIndex}`).forEach(points => {
      points.sort((a, b) => a.offsetMillis - b.offsetMillis);
      const player = participantById(snapshot, points[0].playerUuid);
      if (!player) return;
      ctx.beginPath();
      ctx.strokeStyle = player.colorHex;
      ctx.lineWidth = selectedPlayerUuid === player.uuid ? 3 : 2;
      points.forEach((point, index) => {
        const total = toggles.absorptionSeparate ? Number(point.health || 0) : Math.min(maxHealth, (Number(point.health) || 0) + (Number(point.absorption) || 0));
        const x = 40 + (point.offsetMillis / duration) * (canvas.width - 60);
        const y = graphY(total, maxHealth, canvas);
        if (index === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
      });
      ctx.stroke();
      if (toggles.absorptionSeparate) {
        ctx.beginPath();
        ctx.strokeStyle = '#fbbf24';
        ctx.lineWidth = 1.2;
        points.forEach((point, index) => {
          const total = Math.min(maxHealth, (Number(point.health) || 0) + (Number(point.absorption) || 0));
          const x = 40 + (point.offsetMillis / duration) * (canvas.width - 60);
          const y = graphY(total, maxHealth, canvas);
          if (index === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
        });
        ctx.stroke();
      }
    });
  }

  function drawPaths(ctx, canvas, snapshot, bounds, paths, visiblePlayersSet, toggles, currentTime, effectiveSelectedPlayerUuid, markerHits) {
    const grouped = groupBy(paths, point => `${point.playerUuid}:${point.lifeIndex}`);
    grouped.forEach(points => {
      points.sort((a, b) => a.offsetMillis - b.offsetMillis);
      let previous = null;
      const player = participantById(snapshot, points[0].playerUuid);
      if (!player || (!toggles.spectator && String(points[0].gameMode || '').toUpperCase() === 'SPECTATOR')) return;
      points.forEach(point => {
        const [x, y] = project(bounds, point.x, point.z, canvas.width, canvas.height);
        markerHits.push({ uuid: point.playerUuid, x, y, r: 9 });
        ctx.save();
        ctx.fillStyle = player.colorHex;
        ctx.globalAlpha = effectiveSelectedPlayerUuid && effectiveSelectedPlayerUuid !== player.uuid ? 0.45 : 1;
        ctx.beginPath();
        ctx.arc(x, y, 4.5, 0, Math.PI * 2);
        ctx.fill();
        ctx.restore();
        if (previous) {
          const [px, py] = project(bounds, previous.x, previous.z, canvas.width, canvas.height);
          const sameWorld = previous.world === point.world;
          const jump = sameWorld && !!point.isTeleport;
          const spectator = String(point.gameMode || '').toUpperCase() === 'SPECTATOR' || String(previous.gameMode || '').toUpperCase() === 'SPECTATOR';
          ctx.save();
          ctx.strokeStyle = player.colorHex;
          ctx.lineWidth = effectiveSelectedPlayerUuid === player.uuid ? 3 : 2;
          ctx.globalAlpha = spectator ? 0.45 : 0.8;
          if (spectator) {
            ctx.setLineDash([4, 4]);
          } else if (jump) {
            ctx.setLineDash([7, 6]);
          }
          if (!jump || toggles.jumps) {
            ctx.beginPath();
            ctx.moveTo(px, py);
            ctx.lineTo(x, y);
            ctx.stroke();
          }
          ctx.restore();
        }
        previous = point;
      });
    });
  }

  function drawProjectiles(ctx, canvas, snapshot, bounds, selectedWorld, currentTime, visiblePlayersSet) {
    (snapshot.projectiles || []).forEach(projectile => {
      const shooter = projectile.shooterUuid ? participantById(snapshot, projectile.shooterUuid) : null;
      if (projectile.shooterUuid && !visiblePlayersSet.has(projectile.shooterUuid) && projectile.kind !== 'hostile') return;
      const points = (projectile.points || []).filter(point => point.offsetMillis <= currentTime && point.world === selectedWorld);
      if (projectile.launchedAtOffsetMillis > currentTime || points.length < 2) return;
      ctx.save();
      const baseColor = projectile.colorHex || (shooter ? shooter.colorHex : '#d4dbe2');
      const pearlColor = tintColor(baseColor, 0.32);
      ctx.strokeStyle = projectile.kind === 'hostile' ? '#f97316' : projectile.kind === 'ender_pearl' ? pearlColor : baseColor;
      ctx.globalAlpha = projectile.kind === 'hostile' ? 0.7 : 0.38;
      ctx.lineWidth = projectile.kind === 'ender_pearl' ? 2 : 1.25;
      ctx.setLineDash(projectile.kind === 'ender_pearl' ? [2, 4] : [4, 5]);
      ctx.beginPath();
      points.forEach((point, index) => {
        const [x, y] = project(bounds, point.x, point.z, canvas.width, canvas.height);
        if (index === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
      });
      ctx.stroke();
      ctx.restore();
    });
  }

  function drawMobs(ctx, canvas, snapshot, bounds, selectedWorld, currentTime) {
    (snapshot.mobs || []).forEach(mob => {
      const points = (mob.points || []).filter(point => point.offsetMillis <= currentTime && point.world === selectedWorld);
      if (mob.startedAtOffsetMillis > currentTime || points.length < 1) return;
      ctx.save();
      ctx.strokeStyle = mob.colorHex || '#f97316';
      ctx.globalAlpha = 0.35;
      ctx.lineWidth = 1.2;
      ctx.setLineDash([2, 6]);
      ctx.beginPath();
      points.forEach((point, index) => {
        const [x, y] = project(bounds, point.x, point.z, canvas.width, canvas.height);
        if (index === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
      });
      ctx.stroke();
      const last = points[points.length - 1];
      const [mx, my] = project(bounds, last.x, last.z, canvas.width, canvas.height);
      ctx.fillStyle = mob.colorHex || '#f97316';
      ctx.fillRect(mx - 3, my - 3, 6, 6);
      ctx.restore();
    });
  }

  function drawMarkers(ctx, canvas, snapshot, bounds, selectedWorld, currentTime, toggles, effectiveSelectedPlayerUuid) {
    const visible = (snapshot.markers || [])
      .filter(marker => marker.offsetMillis <= currentTime && (!marker.endedAtOffsetMillis || marker.endedAtOffsetMillis > currentTime) && marker.world === selectedWorld)
      .filter(marker => toggles.spawns || (marker.kind !== 'spawn' && marker.kind !== 'world_spawn'))
      .filter(marker => toggles.jumps || marker.kind !== 'jump')
      .sort((a, b) => a.offsetMillis - b.offsetMillis);
    const spawnOrder = new Map();
    let spawnCount = 0;
    visible.forEach(marker => {
      if (marker.kind === 'spawn') {
        spawnCount += 1;
        spawnOrder.set(marker.markerUuid, spawnCount);
      }
    });
    visible.forEach(marker => {
      const [x, y] = project(bounds, marker.x, marker.z, canvas.width, canvas.height);
      const player = marker.playerUuid ? participantById(snapshot, marker.playerUuid) : null;
      const selected = effectiveSelectedPlayerUuid ? participantById(snapshot, effectiveSelectedPlayerUuid) : null;
      const ownerList = String(marker.description || '');
      const relevantToSelected = !selected || ownerList.includes(selected.name);
      const staleSpawn = marker.kind === 'spawn' && !relevantToSelected;
      const markerColor = marker.kind === 'world_spawn'
        ? '#9ca3af'
        : marker.kind === 'spawn'
          ? (relevantToSelected ? '#cbd5e1' : '#64748b')
          : marker.kind.includes('dimension')
            ? '#38bdf8'
            : marker.kind === 'portal'
              ? '#a78bfa'
              : (marker.colorHex || (player ? player.colorHex : '#f59e0b'));
      ctx.save();
      ctx.strokeStyle = markerColor;
      ctx.lineWidth = marker.kind === 'portal' ? 2 : 1.5;
      ctx.globalAlpha = staleSpawn ? 0.45 : 0.95;
      ctx.setLineDash(marker.kind === 'portal' ? [6, 4] : [2, 4]);
      ctx.beginPath();
      ctx.arc(x, y, 7, 0, Math.PI * 2);
      ctx.stroke();
      ctx.restore();
      ctx.fillStyle = marker.kind === 'spawn' ? markerColor : (marker.colorHex || (player ? player.colorHex : '#cdd7e1'));
      const label = marker.kind === 'spawn' ? `Spawn #${spawnOrder.get(marker.markerUuid) || 1}` : (marker.label || marker.kind);
      ctx.fillText(label, x + 9, y + 4);
    });
  }

  function drawEndEntities(ctx, canvas, snapshot, bounds, selectedWorld, currentTime) {
    const dragon = (snapshot.dragon || []).filter(sample => sample.offsetMillis <= currentTime && sample.world === selectedWorld).slice(-1)[0];
    if (dragon) {
      const [x, y] = project(bounds, dragon.x, dragon.z, canvas.width, canvas.height);
      ctx.save();
      ctx.strokeStyle = '#ef4444';
      ctx.lineWidth = 3;
      ctx.beginPath();
      ctx.arc(x, y, 10, 0, Math.PI * 2);
      ctx.stroke();
      ctx.fillStyle = '#ef4444';
      ctx.fillText(`Dragon ${dragon.health.toFixed(0)}/${dragon.maxHealth.toFixed(0)}`, x + 12, y - 10);
      ctx.restore();
    }
    (snapshot.endCrystals || []).filter(crystal => crystal.spawnedAtOffsetMillis <= currentTime && (!crystal.destroyedAtOffsetMillis || crystal.destroyedAtOffsetMillis > currentTime) && crystal.world === selectedWorld).forEach(crystal => {
      const [x, y] = project(bounds, crystal.x, crystal.z, canvas.width, canvas.height);
      ctx.save();
      ctx.fillStyle = '#c084fc';
      ctx.fillRect(x - 4, y - 4, 8, 8);
      ctx.restore();
    });
  }

  function buildSummary(snapshot) {
    if (!snapshot || !snapshot.metadata) return '';
    const participantCount = (snapshot.participants || []).length;
    const duration = maxOffset(snapshot);
    return `${snapshot.metadata.runnerName || 'Unknown runner'} · ${snapshot.metadata.outcome || 'Unknown outcome'} · ${participantCount} participants · ${formatDuration(duration)} · started ${formatDate(snapshot.metadata.startedAtEpochMillis)}`;
  }

  function buildTimelineRows(snapshot, currentTime, activeKindsSet, selectedPlayerUuid) {
    if (!snapshot) return [];
    return (snapshot.timeline || [])
      .filter(entry => Math.abs(entry.offsetMillis - currentTime) <= 180000)
      .filter(entry => activeKindsSet.has(entry.kind))
      .filter(entry => !selectedPlayerUuid || entry.playerUuid === selectedPlayerUuid)
      .sort((a, b) => a.offsetMillis - b.offsetMillis)
      .map(entry => enrichTimelineEntry(snapshot, entry));
  }

  function buildChatRows(snapshot, selectedPlayerUuid) {
    if (!snapshot) return [];
    return (snapshot.chat || [])
      .filter(entry => entry.kind === 'chat' || entry.kind === 'whisper' || entry.kind === 'advancement')
      .filter(entry => !selectedPlayerUuid || entry.playerUuid === selectedPlayerUuid)
      .sort((a, b) => a.offsetMillis - b.offsetMillis);
  }

  function groupParticipants(snapshot) {
    const grouped = { RUNNER: [], HUNTERS: [], SPECTATORS: [] };
    if (!snapshot) return grouped;
    (snapshot.participants || []).forEach(player => {
      const key = player.role === 'RUNNER' ? 'RUNNER' : player.role === 'HUNTER' ? 'HUNTERS' : 'SPECTATORS';
      grouped[key].push(player);
    });
    return grouped;
  }

  function toggleVisiblePlayer(current, uuid) {
    const next = new Set(current);
    if (next.has(uuid)) next.delete(uuid); else next.add(uuid);
    return Array.from(next);
  }

  function toggleTimelineKind(current, kind) {
    const next = new Set(current);
    if (next.has(kind)) next.delete(kind); else next.add(kind);
    return Array.from(next);
  }

  function enrichTimelineEntry(snapshot, entry) {
    const match = (list, predicate) => (list || []).find(candidate => candidate.playerUuid === entry.playerUuid && candidate.offsetMillis === entry.offsetMillis && predicate(candidate));
    if (entry.kind === 'food') {
      const food = match(snapshot.food, () => true);
      if (food) return { ...entry, detail: `${food.food}/20 food · ${Number(food.saturation || 0).toFixed(1)} saturation · ${(Number(food.health || 0) + Number(food.absorption || 0)).toFixed(1)} hp` };
    }
    if (entry.kind === 'effect') {
      const effect = match(snapshot.effects, () => true);
      if (effect) {
        const bits = [];
        if (effect.amplifier != null) bits.push(`${effect.prettyName} ${roman((effect.amplifier || 0) + 1)}`);
        if (effect.durationTicks != null) bits.push(`${Math.max(0, Math.round((effect.durationTicks || 0) / 20))}s`);
        if (effect.cause) bits.push(effect.cause);
        return { ...entry, detail: bits.join(' · ') };
      }
    }
    if (entry.kind === 'shield') {
      const block = match(snapshot.blocks, () => true);
      if (block) return { ...entry, detail: `${Number(block.blockedDamage || 0).toFixed(1)} blocked at ${formatCoord(block.location)}` };
    }
    if (entry.kind === 'totem') {
      const totem = match(snapshot.totems, () => true);
      if (totem) return { ...entry, detail: formatCoord(totem.location) };
    }
    return entry;
  }

  function formatCoord(location) {
    if (!location) return '';
    return `${Number(location.x || 0).toFixed(1)}, ${Number(location.y || 0).toFixed(1)}, ${Number(location.z || 0).toFixed(1)}`;
  }

  function recentPlayerEntries(list, playerUuid, currentTime, limit) {
    return (list || [])
      .filter(entry => entry.playerUuid === playerUuid && entry.offsetMillis <= currentTime)
      .sort((a, b) => b.offsetMillis - a.offsetMillis)
      .slice(0, limit || 3);
  }

  function selectionLabel(snapshot, selectedPlayer, hoveredPlayerUuid) {
    if (!snapshot || !selectedPlayer) return 'No player selected';
    return `${selectedPlayer.name} · ${hoveredPlayerUuid ? 'hover' : 'pinned'}`;
  }

  function playerChipClass(player, visiblePlayersSet, selectedPlayerUuid) {
    const active = selectedPlayerUuid === player.uuid;
    const hidden = !visiblePlayersSet.has(player.uuid);
    return `player-chip${active ? ' active' : ''}${hidden ? ' hidden' : ''}`;
  }

  function participantById(snapshot, uuid) {
    return !snapshot ? null : (snapshot.participants || []).find(entry => entry.uuid === uuid) || null;
  }

  function latestPoint(snapshot, playerUuid, currentTime) {
    if (!snapshot || !playerUuid) return null;
    const points = (snapshot.paths || []).filter(point => point.playerUuid === playerUuid && point.offsetMillis <= currentTime);
    if (!points.length) return null;
    points.sort((a, b) => a.offsetMillis - b.offsetMillis);
    return points[points.length - 1];
  }

  function preferredWorld(snapshot) {
    const runnerUuid = snapshot && snapshot.metadata ? snapshot.metadata.runnerUuid : null;
    const lastRunnerPoint = (snapshot.paths || []).filter(point => point.playerUuid === runnerUuid).sort((a, b) => a.offsetMillis - b.offsetMillis).pop();
    return lastRunnerPoint ? lastRunnerPoint.world : ((snapshot.paths || [])[0] ? snapshot.paths[0].world : 'minecraft:overworld');
  }

  function maxOffset(snapshot) {
    const offsets = [];
    (snapshot.paths || []).forEach(point => offsets.push(point.offsetMillis));
    (snapshot.timeline || []).forEach(entry => offsets.push(entry.offsetMillis));
    (snapshot.chat || []).forEach(entry => offsets.push(entry.offsetMillis));
    (snapshot.food || []).forEach(entry => offsets.push(entry.offsetMillis));
    (snapshot.effects || []).forEach(entry => offsets.push(entry.offsetMillis));
    (snapshot.totems || []).forEach(entry => offsets.push(entry.offsetMillis));
    (snapshot.blocks || []).forEach(entry => offsets.push(entry.offsetMillis));
    return offsets.length ? Math.max(...offsets) : 0;
  }

  function uniqueWorlds(snapshot) {
    const worlds = new Set();
    (snapshot.paths || []).forEach(point => point.world && worlds.add(point.world));
    (snapshot.markers || []).forEach(marker => marker.world && worlds.add(marker.world));
    return Array.from(worlds);
  }

  function uniqueTimelineKinds(snapshot) {
    const seen = new Set();
    const ordered = [];
    (snapshot.timeline || []).forEach(entry => {
      if (!seen.has(entry.kind)) {
        seen.add(entry.kind);
        ordered.push(entry.kind);
      }
    });
    return ordered;
  }

  function projectilePoints(snapshot, currentTime, selectedWorld) {
    return (snapshot.projectiles || []).flatMap(projectile => (projectile.points || []).filter(point => point.offsetMillis <= currentTime && point.world === selectedWorld));
  }
  function mobPoints(snapshot, currentTime, selectedWorld) {
    return (snapshot.mobs || []).flatMap(mob => (mob.points || []).filter(point => point.offsetMillis <= currentTime && point.world === selectedWorld));
  }
  function dragonPoints(snapshot, currentTime, selectedWorld) {
    return (snapshot.dragon || []).filter(entry => entry.offsetMillis <= currentTime && entry.world === selectedWorld);
  }
  function crystalPoints(snapshot, currentTime, selectedWorld) {
    return (snapshot.endCrystals || []).filter(entry => entry.spawnedAtOffsetMillis <= currentTime && (!entry.destroyedAtOffsetMillis || entry.destroyedAtOffsetMillis > currentTime) && entry.world === selectedWorld);
  }

  function computeBounds(...groups) {
    const points = groups.flat().filter(Boolean);
    let minX = Infinity;
    let maxX = -Infinity;
    let minZ = Infinity;
    let maxZ = -Infinity;
    points.forEach(point => {
      minX = Math.min(minX, point.x);
      maxX = Math.max(maxX, point.x);
      minZ = Math.min(minZ, point.z);
      maxZ = Math.max(maxZ, point.z);
    });
    if (!points.length) return { minX: -1, maxX: 1, minZ: -1, maxZ: 1 };
    if (minX === maxX) { minX -= 1; maxX += 1; }
    if (minZ === maxZ) { minZ -= 1; maxZ += 1; }
    return { minX, maxX, minZ, maxZ };
  }

  function project(bounds, x, z, width, height) {
    const padding = 28;
    const usableWidth = width - padding * 2;
    const usableHeight = height - padding * 2;
    const nx = (x - bounds.minX) / (bounds.maxX - bounds.minX || 1);
    const nz = (z - bounds.minZ) / (bounds.maxZ - bounds.minZ || 1);
    return [padding + nx * usableWidth, height - padding - nz * usableHeight];
  }

  function drawMapGrid(ctx, canvas, bounds) {
    ctx.strokeStyle = '#22303d';
    ctx.lineWidth = 1;
    for (let i = 0; i <= 10; i += 1) {
      const x = 28 + (i / 10) * (canvas.width - 56);
      ctx.beginPath();
      ctx.moveTo(x, 28);
      ctx.lineTo(x, canvas.height - 28);
      ctx.stroke();
      const z = 28 + (i / 10) * (canvas.height - 56);
      ctx.beginPath();
      ctx.moveTo(28, z);
      ctx.lineTo(canvas.width - 28, z);
      ctx.stroke();
    }
    ctx.fillStyle = '#9fb0c0';
    ctx.fillText(`X ${bounds.minX.toFixed(1)} .. ${bounds.maxX.toFixed(1)}`, 34, 20);
    ctx.fillText(`Z ${bounds.minZ.toFixed(1)} .. ${bounds.maxZ.toFixed(1)}`, 260, 20);
  }

  function clearCanvas(ctx, canvas) {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = '#0d141b';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.font = '14px system-ui';
    ctx.textBaseline = 'alphabetic';
    ctx.textAlign = 'left';
  }

  function drawEmpty(ctx, canvas, text) {
    ctx.fillStyle = '#9fb0c0';
    ctx.textAlign = 'center';
    ctx.fillText(text, canvas.width / 2, canvas.height / 2);
    ctx.textAlign = 'left';
  }

  function groupBy(items, keyFn) {
    const map = new Map();
    items.forEach(item => {
      const key = keyFn(item);
      if (!map.has(key)) map.set(key, []);
      map.get(key).push(item);
    });
    return map;
  }

  function graphY(value, maxHealth, canvas) {
    return canvas.height - 20 - ((Math.max(0, Math.min(maxHealth, value)) / maxHealth) * (canvas.height - 40));
  }

  function renderHearts(health, maxHealth, absorption, separateAbsorption) {
    const clamped = Math.max(0, Math.min(maxHealth, health));
    const red = '♥'.repeat(Math.round(clamped / 2));
    const gold = absorption > 0 ? '✦'.repeat(Math.round(absorption / 2)) : '';
    return separateAbsorption ? `${red}${gold ? ` ${gold}` : ''}` : `${red}${gold}` || '—';
  }
  function renderFood(food) { return '🍗'.repeat(Math.max(0, Math.round(food / 2))) || '—'; }
  function shortenWorld(world) { return String(world || '').replace('minecraft:', ''); }
  function formatDuration(ms) {
    const total = Math.floor(ms / 1000);
    const hours = Math.floor(total / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    const seconds = total % 60;
    if (hours > 0) return `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
    return `${minutes}:${String(seconds).padStart(2, '0')}`;
  }
  function formatDate(ms) { return new Date(ms).toLocaleString(); }
  function escapeHtml(value) { return String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;'); }
  function escapeClass(value) { return String(value || '').replace(/[^a-zA-Z0-9_-]/g, '-'); }
  function cachedAvatarUrl(uuid) {
    const remote = `https://crafatar.com/avatars/${encodeURIComponent(uuid)}?size=16&overlay`;
    try {
      const remembered = window.localStorage.getItem(`peoplehunt-avatar-url:${uuid}`);
      if (remembered) return remembered;
      window.localStorage.setItem(`peoplehunt-avatar-url:${uuid}`, remote);
    } catch (_) {
      return remote;
    }
    return remote;
  }
  async function primeAvatarCache(participants) {
    if (!('caches' in window)) return;
    const cache = await window.caches.open('peoplehunt-avatars-v1');
    await Promise.all((participants || []).map(async participant => {
      const url = cachedAvatarUrl(participant.uuid);
      if (await cache.match(url)) return;
      try {
        const response = await fetch(url, { cache: 'force-cache', mode: 'cors' });
        if (response.ok) await cache.put(url, response.clone());
      } catch (_) {
        // Ignore offline/cache failures and fall back to the inline SVG avatar.
      }
    }));
  }
  function svgAvatar(name, seed) {
    const letter = escapeHtml((String(name || '?').trim()[0] || '?').toUpperCase());
    const color = tintColor(colorFromSeed(seed || name || '?'), 0.1);
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16"><rect width="16" height="16" rx="3" fill="${color}"/><text x="8" y="11" font-size="9" text-anchor="middle" fill="#ffffff" font-family="system-ui,sans-serif">${letter}</text></svg>`;
    return `data:image/svg+xml,${encodeURIComponent(svg)}`;
  }
  function colorFromSeed(seed) {
    const value = String(seed || 'seed');
    let hash = 0;
    for (let index = 0; index < value.length; index += 1) hash = ((hash << 5) - hash) + value.charCodeAt(index);
    const r = 96 + Math.abs(hash & 0x3f);
    const g = 96 + Math.abs((hash >> 6) & 0x3f);
    const b = 96 + Math.abs((hash >> 12) & 0x3f);
    return rgbToHex(r, g, b);
  }
  function tintColor(hex, amount = 0) {
    const color = String(hex || '#cdd7e1').replace('#', '');
    const parsed = color.length === 3 ? color.split('').map(ch => ch + ch).join('') : color.padEnd(6, '0');
    const r = parseInt(parsed.slice(0, 2), 16);
    const g = parseInt(parsed.slice(2, 4), 16);
    const b = parseInt(parsed.slice(4, 6), 16);
    const mix = value => Math.max(0, Math.min(255, Math.round(value + (255 - value) * amount)));
    return rgbToHex(mix(r), mix(g), mix(b));
  }
  function rgbToHex(r, g, b) {
    return `#${[r, g, b].map(value => value.toString(16).padStart(2, '0')).join('')}`;
  }
  function roman(value) { return ['I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X'][Math.max(0, Math.min(9, value - 1))] || String(value); }
  function slotName(slot) {
    if (slot === 100) return 'Helmet';
    if (slot === 101) return 'Chest';
    if (slot === 102) return 'Leggings';
    if (slot === 103) return 'Boots';
    if (slot === 150) return 'Offhand';
    return `Slot ${slot}`;
  }
  function toggleSpecs() {
    return [
      { key: 'projectiles', label: 'Projectiles' },
      { key: 'jumps', label: 'Jump markers' },
      { key: 'spectator', label: 'Spectator paths' },
      { key: 'threats', label: 'Threats' },
      { key: 'spawns', label: 'Spawn markers' },
      { key: 'end', label: 'Dragon and crystals' },
      { key: 'absorptionSeparate', label: 'Separate absorption' }
    ];
  }

  render(html`<${PeopleHuntApp} />`, document.getElementById('app'));
})();
