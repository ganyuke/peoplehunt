(() => {
  const reportId = window.PEOPLEHUNT_REPORT_ID;
  const source = reportId === 'LOCAL_EXPORT' ? 'snapshot.json' : `/api/report/${reportId}`;

  const summaryEl = document.getElementById('summary');
  const scrubberEl = document.getElementById('scrubber');
  const timeLabelEl = document.getElementById('timeLabel');
  const tabsEl = document.getElementById('dimensionTabs');
  const playersEl = document.getElementById('playerList');
  const timelineEl = document.getElementById('timeline');
  const chatEl = document.getElementById('chatLog');
  const mapCanvas = document.getElementById('mapCanvas');
  const healthCanvas = document.getElementById('healthCanvas');
  const mapCtx = mapCanvas.getContext('2d');
  const healthCtx = healthCanvas.getContext('2d');

  let snapshot = null;
  let selectedWorld = null;
  let visiblePlayers = new Set();
  let focusedPlayer = null;

  fetch(source)
    .then(response => {
      if (!response.ok) throw new Error(`Failed to load report: ${response.status}`);
      return response.json();
    })
    .then(data => {
      snapshot = data;
      initialize();
    })
    .catch(error => {
      summaryEl.textContent = error.message;
    });

  function initialize() {
    const duration = Math.max(0, snapshot.metadata.endedAtEpochMillis - snapshot.metadata.startedAtEpochMillis);
    scrubberEl.max = String(duration);
    scrubberEl.value = String(duration);

    const participantMap = new Map(snapshot.participants.map(player => [player.uuid, player]));
    snapshot.participants.forEach(player => visiblePlayers.add(player.uuid));

    const worlds = [...new Set(snapshot.paths.map(point => point.world))];
    selectedWorld = worlds[0] || 'unknown';

    summaryEl.innerHTML = [
      `<div><strong>Outcome:</strong> ${escapeHtml(snapshot.metadata.outcome)}</div>`,
      `<div><strong>Runner:</strong> ${escapeHtml(snapshot.metadata.runnerName)}</div>`,
      `<div><strong>Started:</strong> ${formatDate(snapshot.metadata.startedAtEpochMillis)}</div>`,
      `<div><strong>Elapsed:</strong> ${formatDuration(snapshot.metadata.endedAtEpochMillis - snapshot.metadata.startedAtEpochMillis)}</div>`
    ].join('');

    tabsEl.innerHTML = '';
    worlds.forEach(world => {
      const button = document.createElement('button');
      button.className = `tab${world === selectedWorld ? ' active' : ''}`;
      button.textContent = shortenWorld(world);
      button.addEventListener('click', () => {
        selectedWorld = world;
        renderTabs(worlds);
        renderAll();
      });
      tabsEl.appendChild(button);
    });

    playersEl.innerHTML = '';
    snapshot.participants.forEach(player => {
      const button = document.createElement('button');
      button.className = 'player-chip active';
      button.innerHTML = `<span class="swatch" style="background:${player.colorHex}"></span>${escapeHtml(player.name)} <span class="legend-muted">(${escapeHtml(player.role.toLowerCase())})</span>`;
      button.addEventListener('click', () => {
        if (visiblePlayers.has(player.uuid)) {
          visiblePlayers.delete(player.uuid);
          button.classList.remove('active');
        } else {
          visiblePlayers.add(player.uuid);
          button.classList.add('active');
        }
        focusedPlayer = player.uuid;
        autoSwitchDimensionForFocused();
        renderAll();
      });
      playersEl.appendChild(button);
    });

    snapshot.chat.forEach(line => {
      const row = document.createElement('div');
      row.className = 'chat-row';
      row.innerHTML = `<div class="chat-time">${formatDuration(line.offsetMillis)}</div><div>${line.html}</div>`;
      chatEl.appendChild(row);
    });
    if (!snapshot.chat.length) {
      chatEl.innerHTML = '<div class="empty">No chat captured.</div>';
    }

    scrubberEl.addEventListener('input', renderAll);
    renderAll();

    function renderTabs(worldsList) {
      [...tabsEl.children].forEach((child, index) => {
        child.classList.toggle('active', worldsList[index] === selectedWorld);
      });
    }

    function autoSwitchDimensionForFocused() {
      if (!focusedPlayer) return;
      const currentTime = Number(scrubberEl.value);
      const latestPoint = snapshot.paths
        .filter(point => point.playerUuid === focusedPlayer && point.offsetMillis <= currentTime)
        .sort((a, b) => a.offsetMillis - b.offsetMillis)
        .pop();
      if (latestPoint && latestPoint.world !== selectedWorld) {
        selectedWorld = latestPoint.world;
        renderTabs(worlds);
      }
    }
  }

  function renderAll() {
    if (!snapshot) return;
    const time = Number(scrubberEl.value);
    timeLabelEl.textContent = formatDuration(time);
    renderMap(time);
    renderHealth(time);
    renderTimeline(time);
  }

  function renderMap(time) {
    clearCanvas(mapCtx, mapCanvas);
    const filtered = snapshot.paths.filter(point => point.world === selectedWorld && point.offsetMillis <= time && visiblePlayers.has(point.playerUuid));
    if (!filtered.length) {
      drawEmpty(mapCtx, mapCanvas, `No visible paths for ${shortenWorld(selectedWorld)}.`);
      return;
    }
    const bounds = computeBounds(filtered);
    drawMapGrid(bounds);

    const grouped = groupBy(filtered, point => point.playerUuid);
    for (const [uuid, points] of grouped.entries()) {
      const player = snapshot.participants.find(entry => entry.uuid === uuid);
      if (!player) continue;
      points.sort((a, b) => a.offsetMillis - b.offsetMillis);
      mapCtx.beginPath();
      mapCtx.lineWidth = 2;
      mapCtx.strokeStyle = player.colorHex;
      points.forEach((point, index) => {
        const [sx, sy] = project(bounds, point.x, point.z, mapCanvas.width, mapCanvas.height);
        if (index === 0) mapCtx.moveTo(sx, sy); else mapCtx.lineTo(sx, sy);
      });
      mapCtx.stroke();

      const current = points[points.length - 1];
      const [cx, cy] = project(bounds, current.x, current.z, mapCanvas.width, mapCanvas.height);
      mapCtx.fillStyle = player.colorHex;
      mapCtx.beginPath();
      mapCtx.arc(cx, cy, 5, 0, Math.PI * 2);
      mapCtx.fill();
      mapCtx.fillStyle = '#e6edf3';
      mapCtx.fillText(player.name, cx + 8, cy - 8);
    }
  }

  function renderHealth(time) {
    clearCanvas(healthCtx, healthCanvas);
    const filtered = snapshot.paths.filter(point => point.offsetMillis <= time && visiblePlayers.has(point.playerUuid));
    if (!filtered.length) {
      drawEmpty(healthCtx, healthCanvas, 'No visible health samples.');
      return;
    }
    const duration = Math.max(1, Number(scrubberEl.max));
    healthCtx.strokeStyle = '#41586f';
    healthCtx.beginPath();
    for (let i = 0; i <= 20; i += 5) {
      const y = healthCanvas.height - 20 - (i / 20) * (healthCanvas.height - 40);
      healthCtx.moveTo(40, y);
      healthCtx.lineTo(healthCanvas.width - 10, y);
      healthCtx.fillStyle = '#9fb0c0';
      healthCtx.fillText(String(i), 10, y + 4);
    }
    healthCtx.stroke();

    const grouped = groupBy(filtered, point => point.playerUuid);
    for (const [uuid, points] of grouped.entries()) {
      const player = snapshot.participants.find(entry => entry.uuid === uuid);
      if (!player) continue;
      points.sort((a, b) => a.offsetMillis - b.offsetMillis);
      healthCtx.beginPath();
      healthCtx.lineWidth = 2;
      healthCtx.strokeStyle = player.colorHex;
      points.forEach((point, index) => {
        const x = 40 + (point.offsetMillis / duration) * (healthCanvas.width - 60);
        const y = healthCanvas.height - 20 - ((Math.min(20, Math.max(0, point.health)) / 20) * (healthCanvas.height - 40));
        if (index === 0) healthCtx.moveTo(x, y); else healthCtx.lineTo(x, y);
      });
      healthCtx.stroke();
    }
  }

  function renderTimeline(time) {
    const nearby = snapshot.timeline
      .filter(entry => Math.abs(entry.offsetMillis - time) <= 120000)
      .sort((a, b) => a.offsetMillis - b.offsetMillis);
    timelineEl.innerHTML = '';
    if (!nearby.length) {
      timelineEl.innerHTML = '<div class="empty">No timeline entries near this moment.</div>';
      return;
    }
    nearby.forEach(entry => {
      const row = document.createElement('div');
      row.className = 'timeline-row';
      row.innerHTML = `<div class="timeline-time">${formatDuration(entry.offsetMillis)}</div><div><strong>${escapeHtml(entry.playerName || 'system')}</strong> — ${escapeHtml(entry.description)}</div>`;
      timelineEl.appendChild(row);
    });
  }

  function computeBounds(points) {
    let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
    points.forEach(point => {
      minX = Math.min(minX, point.x);
      maxX = Math.max(maxX, point.x);
      minZ = Math.min(minZ, point.z);
      maxZ = Math.max(maxZ, point.z);
    });
    if (minX === maxX) { minX -= 1; maxX += 1; }
    if (minZ === maxZ) { minZ -= 1; maxZ += 1; }
    return { minX, maxX, minZ, maxZ };
  }

  function project(bounds, x, z, width, height) {
    const padding = 28;
    const usableWidth = width - padding * 2;
    const usableHeight = height - padding * 2;
    const nx = (x - bounds.minX) / (bounds.maxX - bounds.minX);
    const nz = (z - bounds.minZ) / (bounds.maxZ - bounds.minZ);
    return [padding + nx * usableWidth, height - padding - nz * usableHeight];
  }

  function drawMapGrid(bounds) {
    mapCtx.strokeStyle = '#22303d';
    mapCtx.lineWidth = 1;
    for (let i = 0; i <= 10; i++) {
      const x = 28 + (i / 10) * (mapCanvas.width - 56);
      mapCtx.beginPath();
      mapCtx.moveTo(x, 28);
      mapCtx.lineTo(x, mapCanvas.height - 28);
      mapCtx.stroke();
      const z = 28 + (i / 10) * (mapCanvas.height - 56);
      mapCtx.beginPath();
      mapCtx.moveTo(28, z);
      mapCtx.lineTo(mapCanvas.width - 28, z);
      mapCtx.stroke();
    }
    mapCtx.fillStyle = '#9fb0c0';
    mapCtx.fillText(`X ${bounds.minX.toFixed(1)} .. ${bounds.maxX.toFixed(1)}`, 34, 20);
    mapCtx.fillText(`Z ${bounds.minZ.toFixed(1)} .. ${bounds.maxZ.toFixed(1)}`, 260, 20);
  }

  function clearCanvas(ctx, canvas) {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = '#0d141b';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.font = '14px system-ui';
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

  function shortenWorld(world) {
    return world.replace('minecraft:', '');
  }

  function formatDuration(ms) {
    const total = Math.floor(ms / 1000);
    const hours = Math.floor(total / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    const seconds = total % 60;
    if (hours > 0) return `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
    return `${minutes}:${String(seconds).padStart(2, '0')}`;
  }

  function formatDate(ms) {
    return new Date(ms).toLocaleString();
  }

  function escapeHtml(value) {
    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }
})();
