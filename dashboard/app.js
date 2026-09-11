const apiBase = `${window.location.protocol}//${window.location.hostname}:8080`;
const form = document.querySelector("#config-form");
const keyInput = document.querySelector("#api-key");
const notice = document.querySelector("#connection");
const eventsBody = document.querySelector("#events-body");
const alertsList = document.querySelector("#alerts-list");
const hostFilter = document.querySelector("#host-filter");

// Event and alert text is operator-supplied data: escape it before it reaches innerHTML.
function escapeHtml(value) {
  return String(value ?? "").replace(
    /[&<>"']/g,
    (character) =>
      ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&#39;",
      })[character],
  );
}

// The key lives in a closure for the page's lifetime rather than in the DOM or storage:
// it is not readable from the input, not restored after a reload, and not in localStorage.
// This reduces exposure; it is not a session system. A real one needs same-origin serving
// and an HttpOnly cookie, which is a later phase.
let apiKey = "";

function headers() {
  return { "X-EventWatch-Key": apiKey };
}

async function getJson(path) {
  const response = await fetch(`${apiBase}${path}`, { headers: headers() });
  const body = await response.json();
  if (!response.ok) throw new Error(body.message || "Request failed");
  return body;
}

function renderSummary(summary) {
  document.querySelector("#host-count").textContent = summary.hosts ?? "--";
  document.querySelector("#total-events").textContent = summary.total_events;
  document.querySelector("#active-alerts").textContent = summary.active_alerts;
  document.querySelector("#average-cpu").textContent =
    `${Number(summary.average_cpu).toFixed(1)}%`;
  document.querySelector("#average-ram").textContent =
    `${Number(summary.average_ram).toFixed(1)}%`;
}

function renderEvents(events) {
  eventsBody.innerHTML =
    events.items
      .map(
        (event) => `
    <tr><td><span class="badge">${escapeHtml(event.level)}</span></td>
    <td class="host-tag" title="${escapeHtml(event.host_id ?? "")}">${escapeHtml(event.hostname ?? event.host_id ?? "unknown")}</td>
    <td title="${escapeHtml(event.msg)}">${escapeHtml(event.msg)}</td>
    <td>${escapeHtml(new Date(event.timestamp).toLocaleString())}</td><td>${Number(event.cpu_usage).toFixed(1)}%</td>
    <td>${Number(event.ram_usage).toFixed(1)}%</td></tr>`,
      )
      .join("") ||
    '<tr><td colspan="6" class="empty">No events found.</td></tr>';
}

function renderAlerts(alerts) {
  alertsList.innerHTML =
    alerts
      .map(
        (alert) => `
    <div class="alert-card"><strong>${escapeHtml(alert.alert_type)} · ${escapeHtml(alert.status)}</strong>
    <div class="host-tag">${escapeHtml(alert.host_id ?? "unknown")}</div>
    <div>${escapeHtml(alert.message)}</div><div class="alert-meta">${Number(alert.occurrence_count)} occurrence(s) · last seen ${escapeHtml(new Date(alert.last_seen).toLocaleString())}</div>
    <div class="alert-actions"><button data-action="acknowledge" data-key="${escapeHtml(alert.alert_key)}" type="button">Acknowledge</button><button data-action="resolve" data-key="${escapeHtml(alert.alert_key)}" type="button">Resolve</button><button data-action="notifications" data-key="${escapeHtml(alert.alert_key)}" type="button">History</button></div>
    <div class="notification-history" data-history-for="${escapeHtml(alert.alert_key)}"></div></div>`,
      )
      .join("") || '<p class="empty">No active alerts.</p>';
}

async function showNotifications(alertKey) {
  const panel = alertsList.querySelector(
    `[data-history-for="${CSS.escape(alertKey)}"]`,
  );
  if (!panel) return;
  if (panel.innerHTML) {
    panel.innerHTML = "";
    return;
  }
  const deliveries = await getJson(
    `/alerts/${encodeURIComponent(alertKey)}/notifications?limit=10`,
  );
  panel.innerHTML = deliveries.length
    ? `<ul>${deliveries
        .map(
          (delivery) =>
            `<li>${escapeHtml(delivery.event_type)} · ${escapeHtml(delivery.delivery_status)}` +
            `${delivery.http_status ? ` (HTTP ${Number(delivery.http_status)})` : ""}` +
            ` · attempt ${Number(delivery.attempt_number)} · ${escapeHtml(new Date(delivery.attempted_at).toLocaleString())}` +
            `${delivery.error_message ? `<br><span class="empty">${escapeHtml(delivery.error_message)}</span>` : ""}</li>`,
        )
        .join("")}</ul>`
    : '<p class="empty">No delivery attempts recorded.</p>';
}

async function updateAlert(alertKey, action) {
  const response = await fetch(
    `${apiBase}/alerts/${encodeURIComponent(alertKey)}/${action}`,
    {
      method: "POST",
      headers: headers(),
    },
  );
  const body = await response.json();
  if (!response.ok) throw new Error(body.message || "Alert update failed");
  await refresh();
}

function renderHostOptions(hosts) {
  const selected = hostFilter.value;
  const options = hosts
    .map(
      (host) =>
        `<option value="${escapeHtml(host.host_id)}">${escapeHtml(host.hostname ?? host.host_id)}</option>`,
    )
    .join("");
  hostFilter.innerHTML = `<option value="">All machines</option>${options}`;
  // Preserve the selection across refreshes, unless that machine is gone.
  if (selected && hosts.some((host) => host.host_id === selected)) {
    hostFilter.value = selected;
  }
}

async function refresh() {
  if (!apiKey) return;
  try {
    const host = hostFilter.value;
    const eventsPath = host
      ? `/events?limit=50&host_id=${encodeURIComponent(host)}`
      : "/events?limit=50";
    const [summary, events, alerts, hosts] = await Promise.all([
      getJson("/summary"),
      getJson(eventsPath),
      getJson("/alerts"),
      getJson("/hosts"),
    ]);
    renderHostOptions(hosts);
    renderSummary(summary);
    renderEvents(events);
    renderAlerts(alerts);
    notice.textContent = `Connected · updated ${new Date().toLocaleTimeString()}`;
    notice.style.borderColor = "var(--teal)";
  } catch (error) {
    notice.textContent = error.message;
    notice.style.borderColor = "var(--coral)";
  }
}

form.addEventListener("submit", (event) => {
  event.preventDefault();
  if (keyInput.value) {
    apiKey = keyInput.value;
    // Clear the field so the key is not sitting in the DOM for the rest of the session.
    keyInput.value = "";
    keyInput.placeholder = "Connected";
  }
  refresh();
});
document.querySelector("#refresh").addEventListener("click", refresh);
hostFilter.addEventListener("change", refresh);
alertsList.addEventListener("click", (event) => {
  const button = event.target.closest("button[data-action]");
  if (!button) return;
  const handled =
    button.dataset.action === "notifications"
      ? showNotifications(button.dataset.key)
      : updateAlert(button.dataset.key, button.dataset.action);
  handled.catch((error) => {
    notice.textContent = error.message;
    notice.style.borderColor = "var(--coral)";
  });
});
