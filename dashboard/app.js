const apiBase = `${window.location.protocol}//${window.location.hostname}:8080`;
const form = document.querySelector("#config-form");
const keyInput = document.querySelector("#api-key");
const notice = document.querySelector("#connection");
const eventsBody = document.querySelector("#events-body");
const alertsList = document.querySelector("#alerts-list");

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

function headers() {
  return { "X-EventWatch-Key": keyInput.value };
}

async function getJson(path) {
  const response = await fetch(`${apiBase}${path}`, { headers: headers() });
  const body = await response.json();
  if (!response.ok) throw new Error(body.message || "Request failed");
  return body;
}

function renderSummary(summary) {
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
    <td title="${escapeHtml(event.msg)}">${escapeHtml(event.msg)}</td>
    <td>${escapeHtml(new Date(event.timestamp).toLocaleString())}</td><td>${Number(event.cpu_usage).toFixed(1)}%</td>
    <td>${Number(event.ram_usage).toFixed(1)}%</td></tr>`,
      )
      .join("") ||
    '<tr><td colspan="5" class="empty">No events found.</td></tr>';
}

function renderAlerts(alerts) {
  alertsList.innerHTML =
    alerts
      .map(
        (alert) => `
    <div class="alert-card"><strong>${escapeHtml(alert.alert_type)} · ${escapeHtml(alert.status)}</strong>
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

async function refresh() {
  if (!keyInput.value) return;
  try {
    const [summary, events, alerts] = await Promise.all([
      getJson("/summary"),
      getJson("/events?limit=50"),
      getJson("/alerts"),
    ]);
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
  refresh();
});
document.querySelector("#refresh").addEventListener("click", refresh);
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
