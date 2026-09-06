const apiBase = `${window.location.protocol}//${window.location.hostname}:8080`;
const form = document.querySelector("#config-form");
const keyInput = document.querySelector("#api-key");
const notice = document.querySelector("#connection");
const eventsBody = document.querySelector("#events-body");
const alertsList = document.querySelector("#alerts-list");

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
    <tr><td><span class="badge">${event.level}</span></td><td title="${event.msg}">${event.msg}</td>
    <td>${new Date(event.timestamp).toLocaleString()}</td><td>${Number(event.cpu_usage).toFixed(1)}%</td>
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
    <div class="alert-card"><strong>${alert.alert_type} · ${alert.status}</strong>
    <div>${alert.message}</div><div class="alert-meta">${alert.occurrence_count} occurrence(s) · last seen ${new Date(alert.last_seen).toLocaleString()}</div>
    <div class="alert-actions"><button data-action="acknowledge" data-key="${alert.alert_key}" type="button">Acknowledge</button><button data-action="resolve" data-key="${alert.alert_key}" type="button">Resolve</button></div></div>`,
      )
      .join("") || '<p class="empty">No active alerts.</p>';
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
  updateAlert(button.dataset.key, button.dataset.action).catch((error) => {
    notice.textContent = error.message;
    notice.style.borderColor = "var(--coral)";
  });
});
