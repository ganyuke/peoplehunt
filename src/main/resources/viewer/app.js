(function () {
  const raw = window.MANHUNT_VIEWER_DATA || {};

  const sessionSummary = parseJson(raw.sessionSummaryJson, {});
  const pathPoints = parseJsonl(raw.pathPointsJsonl).map(normalizeTimedRecord).filter(Boolean);
  const deathPoints = parseJsonl(raw.deathsJsonl).map(normalizeTimedRecord).filter(Boolean);
  const healthSamples = parseJsonl(raw.healthJsonl).map(normalizeTimedRecord).filter(Boolean);
  const milestonePoints = parseJsonl(raw.milestonesJsonl).map(normalizeTimedRecord).filter(Boolean);

  const players = buildPlayers(sessionSummary, pathPoints, deathPoints, healthSamples);
  const colorByPlayerId = new Map(players.map((player, index) => [player.id, colorForPlayer(player, index)]));

  const allTimestamps = [
    ...pathPoints.map((point) => point.epochMillis),
    ...deathPoints.map((point) => point.epochMillis),
    ...healthSamples.map((point) => point.epochMillis)
  ].filter((value) => Number.isFinite(value));

  const startedAt = parseTimestamp(sessionSummary.startedAt) || Math.min(...allTimestamps, Date.now());
  const endedAt = parseTimestamp(sessionSummary.endedAt) || Math.max(...allTimestamps, startedAt);
  const minTime = Number.isFinite(startedAt) ? startedAt : Date.now();
  const maxTime = Number.isFinite(endedAt) && endedAt >= minTime ? endedAt : minTime;
  const totalDuration = Math.max(1, maxTime - minTime);

  const worlds = Array.from(new Set([
    ...pathPoints.map((point) => point.location?.world),
    ...deathPoints.map((point) => point.location?.world),
    ...healthSamples.map((point) => point.location?.world),
    ...milestonePoints.map((point) => point.location?.world)
  ].filter(Boolean))).sort();

  const state = {
    world: worlds[0] || null,
    currentTime: maxTime,
    playing: false,
    playHandle: null,
    visiblePlayers: new Map(players.map((player) => [player.id, true]))
  };

  const elements = {
    worldSelect: document.getElementById('worldSelect'),
    timeSlider: document.getElementById('timeSlider'),
    timeLabel: document.getElementById('timeLabel'),
    playButton: document.getElementById('playButton'),
    sessionMeta: document.getElementById('sessionMeta'),
    playerLegend: document.getElementById('playerLegend'),
    mapSvg: document.getElementById('mapSvg'),
    healthSvg: document.getElementById('healthSvg'),
    mapStats: document.getElementById('mapStats'),
    healthStats: document.getElementById('healthStats')
  };

  initialize();

  function initialize() {
    renderSessionMeta();
    renderWorldOptions();
    renderLegend();
    bindControls();
    render();
  }

  function bindControls() {
    elements.timeSlider.min = '0';
    elements.timeSlider.max = String(totalDuration);
    elements.timeSlider.value = String(totalDuration);

    elements.timeSlider.addEventListener('input', () => {
      state.currentTime = minTime + Number(elements.timeSlider.value);
      stopPlayback();
      render();
    });

    elements.worldSelect.addEventListener('change', () => {
      state.world = elements.worldSelect.value || null;
      render();
    });

    elements.playButton.addEventListener('click', () => {
      if (state.playing) {
        stopPlayback();
      } else {
        startPlayback();
      }
    });
  }

  function startPlayback() {
    if (state.playing) {
      return;
    }
    state.playing = true;
    elements.playButton.textContent = 'Pause';
    const step = Math.max(1000, Math.floor(totalDuration / 240));
    state.playHandle = window.setInterval(() => {
      if (state.currentTime >= maxTime) {
        stopPlayback();
        return;
      }
      state.currentTime = Math.min(maxTime, state.currentTime + step);
      elements.timeSlider.value = String(state.currentTime - minTime);
      render();
    }, 80);
  }

  function stopPlayback() {
    if (state.playHandle) {
      window.clearInterval(state.playHandle);
      state.playHandle = null;
    }
    state.playing = false;
    elements.playButton.textContent = 'Play';
  }

  function render() {
    renderTimeLabel();
    renderMap();
    renderHealthGraph();
  }

  function renderSessionMeta() {
    const runnerName = sessionSummary.runner?.name || sessionSummary.runnerName || 'Unknown';
    const hunters = Array.isArray(sessionSummary.hunters) ? sessionSummary.hunters : [];
    const durationLabel = formatDuration(totalDuration);
    const chips = [
      statChip('Result', prettyOutcome(sessionSummary.victory || 'NONE')),
      statChip('Runner', runnerName),
      statChip('Hunters', String(hunters.length)),
      statChip('Duration', durationLabel),
      statChip('Reason', sessionSummary.endReason || '—'),
      statChip('Worlds', worlds.length ? worlds.join(', ') : '—')
    ].join('');

    elements.sessionMeta.innerHTML = [
      '<h2>Session</h2>',
      '<p class="subtle">Static viewer data is embedded locally so this page can be opened directly from an export folder.</p>',
      `<div class="stat-grid">${chips}</div>`,
      `<p class="notice">Session ID: ${escapeHtml(sessionSummary.sessionId || 'unknown')}</p>`
    ].join('');
  }

  function renderWorldOptions() {
    if (!worlds.length) {
      elements.worldSelect.innerHTML = '<option value="">No world data</option>';
      elements.worldSelect.disabled = true;
      return;
    }
    elements.worldSelect.disabled = false;
    elements.worldSelect.innerHTML = worlds
      .map((world) => `<option value="${escapeAttribute(world)}">${escapeHtml(world)}</option>`)
      .join('');
    elements.worldSelect.value = state.world;
  }

  function renderLegend() {
    elements.playerLegend.innerHTML = players.map((player) => {
      const checked = state.visiblePlayers.get(player.id) ? 'checked' : '';
      const color = colorByPlayerId.get(player.id);
      return `
        <label class="legend-row">
          <input type="checkbox" data-player-id="${escapeAttribute(player.id)}" ${checked}>
          <span class="swatch" style="background:${color}"></span>
          <span>${escapeHtml(player.name)}</span>
          <span class="legend-role">${escapeHtml(player.role)}</span>
        </label>
      `;
    }).join('');

    Array.from(elements.playerLegend.querySelectorAll('input[type="checkbox"]')).forEach((checkbox) => {
      checkbox.addEventListener('change', () => {
        state.visiblePlayers.set(checkbox.dataset.playerId, checkbox.checked);
        render();
      });
    });
  }

  function renderTimeLabel() {
    const elapsed = Math.max(0, state.currentTime - minTime);
    elements.timeLabel.textContent = `${formatDuration(elapsed)} of ${formatDuration(totalDuration)} shown`;
  }

  function renderMap() {
    if (!state.world) {
      elements.mapSvg.innerHTML = emptySvgState('No path data was recorded for this session.', 620);
      elements.mapStats.textContent = 'No world data available';
      return;
    }

    const visiblePlayerIds = new Set(players.filter((player) => state.visiblePlayers.get(player.id)).map((player) => player.id));
    const visiblePaths = pathPoints.filter((point) => visiblePlayerIds.has(point.playerId) && point.location?.world === state.world && point.epochMillis <= state.currentTime);
    const visibleDeaths = deathPoints.filter((point) => visiblePlayerIds.has(point.playerId) && point.location?.world === state.world && point.epochMillis <= state.currentTime);
    const visibleMilestones = milestonePoints.filter((point) => visiblePlayerIds.has(point.playerId) && point.location?.world === state.world && point.epochMillis <= state.currentTime);

    const worldCoordinates = [
      ...visiblePaths.map((point) => ({ x: point.location.x, z: point.location.z })),
      ...visibleDeaths.map((point) => ({ x: point.location.x, z: point.location.z })),
      ...visibleMilestones.map((point) => ({ x: point.location.x, z: point.location.z }))
    ];

    if (!worldCoordinates.length) {
      elements.mapSvg.innerHTML = emptySvgState(`No visible points for ${state.world} at the current time.`, 620);
      elements.mapStats.textContent = `${state.world} • 0 visible points`;
      return;
    }

    const bounds = computeBounds(worldCoordinates);
    const transform = createMapTransform(bounds, 1000, 620, 48);
    const grouped = groupPathPoints(visiblePaths);
    const currentMarkers = latestMarkers(grouped);

    const parts = [];
    parts.push(`<rect x="0" y="0" width="1000" height="620" fill="#101722"></rect>`);
    parts.push(renderMapGrid(bounds, transform));

    grouped.forEach((points, key) => {
      if (points.length < 2) {
        return;
      }
      const playerId = key.split('::')[0];
      const color = colorByPlayerId.get(playerId) || '#ffffff';
      const polyline = points.map((point) => `${round(transform.x(point.location.x))},${round(transform.y(point.location.z))}`).join(' ');
      const strokeWidth = playerId === sessionSummary.runnerId ? 4 : 3;
      parts.push(`<polyline fill="none" stroke="${color}" stroke-width="${strokeWidth}" stroke-linecap="round" stroke-linejoin="round" points="${polyline}"></polyline>`);
    });

    currentMarkers.forEach((point) => {
      const color = colorByPlayerId.get(point.playerId) || '#ffffff';
      const cx = round(transform.x(point.location.x));
      const cy = round(transform.y(point.location.z));
      const radius = point.playerId === sessionSummary.runnerId ? 9 : 7;
      parts.push(`<circle cx="${cx}" cy="${cy}" r="${radius}" fill="${color}" stroke="#0b0f15" stroke-width="3"></circle>`);
      parts.push(`<text x="${cx + 12}" y="${cy - 12}" class="point-label">${escapeHtml(nameForPlayer(point.playerId))}</text>`);
    });

    visibleDeaths.forEach((death) => {
      const x = round(transform.x(death.location.x));
      const y = round(transform.y(death.location.z));
      const size = death.playerId === sessionSummary.runnerId ? 11 : 9;
      parts.push(`<line class="death-marker" x1="${x - size}" y1="${y - size}" x2="${x + size}" y2="${y + size}"></line>`);
      parts.push(`<line class="death-marker" x1="${x - size}" y1="${y + size}" x2="${x + size}" y2="${y - size}"></line>`);
    });

    visibleMilestones.forEach((milestone) => {
      const x = round(transform.x(milestone.location.x));
      const y = round(transform.y(milestone.location.z));
      parts.push(`<rect x="${x - 4}" y="${y - 4}" width="8" height="8" fill="#f4d35e" stroke="#0b0f15" stroke-width="2"></rect>`);
    });

    elements.mapSvg.innerHTML = parts.join('');
    elements.mapStats.textContent = `${state.world} • ${visiblePaths.length} path points • ${visibleDeaths.length} deaths • ${visibleMilestones.length} milestones`;
  }

  function renderHealthGraph() {
    const visiblePlayerIds = new Set(players.filter((player) => state.visiblePlayers.get(player.id)).map((player) => player.id));
    const visibleHealth = healthSamples.filter((sample) => visiblePlayerIds.has(sample.playerId));

    if (!visibleHealth.length) {
      elements.healthSvg.innerHTML = emptySvgState('No health samples were recorded for the visible players.', 320);
      elements.healthStats.textContent = '0 health samples';
      return;
    }

    const maxHealth = Math.max(20, ...visibleHealth.map((sample) => Number(sample.maxHealth) || Number(sample.health) || 20));
    const graph = createGraphTransform(minTime, maxTime, 0, maxHealth, 1000, 320, { left: 58, right: 18, top: 18, bottom: 42 });
    const grouped = new Map();
    visibleHealth.forEach((sample) => {
      const list = grouped.get(sample.playerId) || [];
      list.push(sample);
      grouped.set(sample.playerId, list);
    });
    grouped.forEach((samples) => samples.sort((a, b) => a.epochMillis - b.epochMillis));

    const parts = [];
    parts.push(`<rect x="0" y="0" width="1000" height="320" fill="#101722"></rect>`);
    parts.push(renderHealthGrid(graph, maxHealth));

    grouped.forEach((samples, playerId) => {
      if (!samples.length) {
        return;
      }
      const color = colorByPlayerId.get(playerId) || '#ffffff';
      const polyline = samples.map((sample) => `${round(graph.x(sample.epochMillis))},${round(graph.y(sample.health))}`).join(' ');
      parts.push(`<polyline fill="none" stroke="${color}" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" points="${polyline}"></polyline>`);

      const currentSample = latestAtOrBefore(samples, state.currentTime);
      if (currentSample) {
        const cx = round(graph.x(currentSample.epochMillis));
        const cy = round(graph.y(currentSample.health));
        parts.push(`<circle cx="${cx}" cy="${cy}" r="5" fill="${color}" stroke="#0b0f15" stroke-width="2"></circle>`);
      }
    });

    const cursorX = round(graph.x(state.currentTime));
    parts.push(`<line class="cursor-line" x1="${cursorX}" y1="${graph.top}" x2="${cursorX}" y2="${graph.bottom}"></line>`);
    parts.push(`<text x="${Math.min(900, cursorX + 8)}" y="${graph.top + 18}" class="cursor-label">${escapeHtml(formatDuration(state.currentTime - minTime))}</text>`);

    elements.healthSvg.innerHTML = parts.join('');
    elements.healthStats.textContent = `${visibleHealth.length} health samples • cursor at ${formatDuration(state.currentTime - minTime)}`;
  }

  function buildPlayers(summary, points, deaths, health) {
    const byId = new Map();
    const runner = summary.runner || (summary.runnerId ? { id: summary.runnerId, name: summary.runnerName || shortId(summary.runnerId), role: 'RUNNER' } : null);
    if (runner && runner.id) {
      byId.set(runner.id, { id: runner.id, name: runner.name || shortId(runner.id), role: runner.role || 'RUNNER' });
    }
    (summary.hunters || []).forEach((hunter) => {
      if (!hunter || !hunter.id) {
        return;
      }
      byId.set(hunter.id, { id: hunter.id, name: hunter.name || shortId(hunter.id), role: hunter.role || 'HUNTER' });
    });
    [...points, ...deaths, ...health].forEach((record) => {
      if (!record.playerId || byId.has(record.playerId)) {
        return;
      }
      byId.set(record.playerId, {
        id: record.playerId,
        name: shortId(record.playerId),
        role: record.role || 'PLAYER'
      });
    });
    return Array.from(byId.values()).sort((a, b) => {
      if (a.role === 'RUNNER') return -1;
      if (b.role === 'RUNNER') return 1;
      return a.name.localeCompare(b.name);
    });
  }

  function groupPathPoints(points) {
    const grouped = new Map();
    points.forEach((point) => {
      const key = `${point.playerId}::${point.lifeNo || 0}`;
      const list = grouped.get(key) || [];
      list.push(point);
      grouped.set(key, list);
    });
    grouped.forEach((list) => list.sort((a, b) => a.epochMillis - b.epochMillis));
    return grouped;
  }

  function latestMarkers(grouped) {
    const markers = [];
    grouped.forEach((points) => {
      if (points.length) {
        markers.push(points[points.length - 1]);
      }
    });
    return markers;
  }

  function latestAtOrBefore(samples, epochMillis) {
    let chosen = null;
    for (const sample of samples) {
      if (sample.epochMillis > epochMillis) {
        break;
      }
      chosen = sample;
    }
    return chosen;
  }

  function parseJson(text, fallback) {
    if (!text || !text.trim()) {
      return fallback;
    }
    try {
      return JSON.parse(text);
    } catch (error) {
      console.error('Failed to parse JSON payload', error);
      return fallback;
    }
  }

  function parseJsonl(text) {
    if (!text || !text.trim()) {
      return [];
    }
    return text
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter(Boolean)
      .map((line) => {
        try {
          return JSON.parse(line);
        } catch (error) {
          console.warn('Skipping malformed JSONL line', line, error);
          return null;
        }
      })
      .filter(Boolean);
  }

  function normalizeTimedRecord(record) {
    if (!record || typeof record !== 'object') {
      return null;
    }
    const epochMillis = Number(record.epochMillis || parseTimestamp(record.at));
    return {
      ...record,
      epochMillis,
      health: Number(record.health),
      maxHealth: Number(record.maxHealth),
      lifeNo: Number(record.lifeNo || 0)
    };
  }

  function parseTimestamp(value) {
    if (!value) {
      return null;
    }
    const parsed = Date.parse(value);
    return Number.isFinite(parsed) ? parsed : null;
  }

  function computeBounds(points) {
    const xs = points.map((point) => point.x);
    const zs = points.map((point) => point.z);
    return {
      minX: Math.min(...xs),
      maxX: Math.max(...xs),
      minZ: Math.min(...zs),
      maxZ: Math.max(...zs)
    };
  }

  function createMapTransform(bounds, width, height, padding) {
    const spanX = Math.max(1, bounds.maxX - bounds.minX);
    const spanZ = Math.max(1, bounds.maxZ - bounds.minZ);
    const usableWidth = width - padding * 2;
    const usableHeight = height - padding * 2;
    const scale = Math.min(usableWidth / spanX, usableHeight / spanZ);
    const offsetX = (width - spanX * scale) / 2;
    const offsetY = (height - spanZ * scale) / 2;
    return {
      x: (worldX) => offsetX + (worldX - bounds.minX) * scale,
      y: (worldZ) => height - offsetY - (worldZ - bounds.minZ) * scale,
      scale,
      width,
      height,
      padding
    };
  }

  function createGraphTransform(minX, maxX, minY, maxY, width, height, margin) {
    const safeMaxX = Math.max(minX + 1, maxX);
    const safeMaxY = Math.max(minY + 1, maxY);
    return {
      left: margin.left,
      right: width - margin.right,
      top: margin.top,
      bottom: height - margin.bottom,
      x: (value) => margin.left + ((value - minX) / (safeMaxX - minX)) * (width - margin.left - margin.right),
      y: (value) => height - margin.bottom - ((value - minY) / (safeMaxY - minY)) * (height - margin.top - margin.bottom)
    };
  }

  function renderMapGrid(bounds, transform) {
    const parts = [];
    const step = niceStep(Math.max(bounds.maxX - bounds.minX, bounds.maxZ - bounds.minZ));

    for (let x = Math.floor(bounds.minX / step) * step; x <= bounds.maxX + step; x += step) {
      const svgX = round(transform.x(x));
      parts.push(`<line class="grid-line" x1="${svgX}" y1="24" x2="${svgX}" y2="596"></line>`);
      parts.push(`<text class="grid-label" x="${svgX + 4}" y="38">X ${escapeHtml(String(x))}</text>`);
    }
    for (let z = Math.floor(bounds.minZ / step) * step; z <= bounds.maxZ + step; z += step) {
      const svgY = round(transform.y(z));
      parts.push(`<line class="grid-line" x1="24" y1="${svgY}" x2="976" y2="${svgY}"></line>`);
      parts.push(`<text class="grid-label" x="28" y="${svgY - 6}">Z ${escapeHtml(String(z))}</text>`);
    }
    return parts.join('');
  }

  function renderHealthGrid(graph, maxHealth) {
    const parts = [];
    const healthStep = Math.max(2, Math.ceil(maxHealth / 5));
    for (let health = 0; health <= maxHealth + 0.001; health += healthStep) {
      const y = round(graph.y(health));
      const klass = health === 0 || health + healthStep > maxHealth ? 'grid-line grid-bold' : 'grid-line';
      parts.push(`<line class="${klass}" x1="${graph.left}" y1="${y}" x2="${graph.right}" y2="${y}"></line>`);
      parts.push(`<text class="grid-label" x="${graph.left - 32}" y="${y + 4}">${escapeHtml(String(health))}</text>`);
    }

    const tickCount = 5;
    for (let tick = 0; tick <= tickCount; tick++) {
      const t = minTime + (totalDuration / tickCount) * tick;
      const x = round(graph.x(t));
      parts.push(`<line class="grid-line" x1="${x}" y1="${graph.top}" x2="${x}" y2="${graph.bottom}"></line>`);
      parts.push(`<text class="grid-label" x="${Math.max(graph.left, x - 18)}" y="${graph.bottom + 24}">${escapeHtml(formatDuration(t - minTime))}</text>`);
    }
    return parts.join('');
  }

  function prettyOutcome(value) {
    switch (value) {
      case 'RUNNER':
        return 'Runner victory';
      case 'HUNTERS':
        return 'Hunters win';
      case 'NONE':
      default:
        return 'Stopped';
    }
  }

  function formatDuration(milliseconds) {
    const totalSeconds = Math.max(0, Math.floor(milliseconds / 1000));
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;
    if (hours > 0) {
      return `${hours}h ${String(minutes).padStart(2, '0')}m ${String(seconds).padStart(2, '0')}s`;
    }
    return `${minutes}m ${String(seconds).padStart(2, '0')}s`;
  }

  function colorForPlayer(player, index) {
    if (player.role === 'RUNNER') {
      return 'hsl(44 92% 62%)';
    }
    const base = hashCode(player.id || player.name || String(index));
    const hue = Math.abs(base) % 360;
    return `hsl(${hue} 72% 62%)`;
  }

  function hashCode(value) {
    let hash = 0;
    for (let index = 0; index < value.length; index++) {
      hash = (hash << 5) - hash + value.charCodeAt(index);
      hash |= 0;
    }
    return hash;
  }

  function statChip(label, value) {
    return `<div class="stat-chip"><span class="label">${escapeHtml(label)}</span><span class="value">${escapeHtml(value)}</span></div>`;
  }

  function nameForPlayer(playerId) {
    const player = players.find((entry) => entry.id === playerId);
    return player ? player.name : shortId(playerId);
  }

  function shortId(value) {
    return value ? value.slice(0, 8) : 'unknown';
  }

  function emptySvgState(message, height) {
    const safeHeight = height || 620;
    return [
      `<rect x="0" y="0" width="1000" height="${safeHeight}" fill="#101722"></rect>`,
      `<text x="500" y="${safeHeight / 2}" text-anchor="middle" class="empty-state">${escapeHtml(message)}</text>`
    ].join('');
  }

  function niceStep(range) {
    const rough = Math.max(1, range / 6);
    const magnitude = Math.pow(10, Math.floor(Math.log10(rough)));
    const normalized = rough / magnitude;
    if (normalized < 1.5) return magnitude;
    if (normalized < 3) return magnitude * 2;
    if (normalized < 7) return magnitude * 5;
    return magnitude * 10;
  }

  function round(value) {
    return Number(value.toFixed(2));
  }

  function escapeHtml(value) {
    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function escapeAttribute(value) {
    return escapeHtml(value).replace(/`/g, '&#96;');
  }
})();
