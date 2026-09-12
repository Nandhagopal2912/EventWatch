// The dashboard is served by the analytics service, so every request is same-origin and the
// session cookie travels with it. There is no base url and no CORS to configure.
const apiBase = "";
const form = document.querySelector("#config-form");
const keyInput = document.querySelector("#api-key");
const notice = document.querySelector("#connection");
const eventsBody = document.querySelector("#events-body");
const alertsList = document.querySelector("#alerts-list");
const hostFilter = document.querySelector("#host-filter");
const ruleForm = document.querySelector("#rule-form");
const ruleHost = document.querySelector("#rule-host");
const rulesBody = document.querySelector("#rules-body");
const ruleDefaults = document.querySelector("#rule-defaults");
const fleetBody = document.querySelector("#fleet-body");
const signOutButton = document.querySelector("#sign-out");
const keyLabel = document.querySelector('label[for="api-key"]');
const signInButton = form.querySelector('button[type="submit"]');
const hostDetail = document.querySelector("#host-detail");
const ruleLabels = {
  HIGH_CPU: "High CPU",
  HIGH_RAM: "High RAM",
  REPEATED_ERROR: "Repeated error",
};
let knownHosts = [];

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

// The key is sent once, to POST /session, and is never held by this page afterwards. The session
// itself lives in an HttpOnly cookie the browser attaches automatically, so no script here - nor
// any script injected into this page - can read it.
let signedIn = false;

async function request(method, path, body) {
  const options = { method, credentials: "same-origin" };
  if (body !== undefined) {
    options.headers = { "Content-Type": "application/json" };
    options.body = JSON.stringify(body);
  }
  const response = await fetch(`${apiBase}${path}`, options);
  if (response.status === 401) {
    signOut(false);
    throw new Error("Session expired. Sign in again.");
  }
  const result = await response.json();
  if (!response.ok) throw new Error(result.message || "Request failed");
  return result;
}

async function getJson(path) {
  return request("GET", path);
}

async function sendJson(method, path, body) {
  return request(method, path, body);
}

function hostLabel(hostId) {
  if (!hostId) return "All machines";
  const host = knownHosts.find((candidate) => candidate.host_id === hostId);
  return host?.hostname ?? hostId;
}

function renderRules(response) {
  rulesBody.innerHTML =
    response.rules
      .map(
        (rule) => `
    <tr><td>${escapeHtml(ruleLabels[rule.rule_type] ?? rule.rule_type)}</td>
    <td class="host-tag">${escapeHtml(hostLabel(rule.host_id))}</td>
    <td>${Number(rule.threshold)}${rule.rule_type === "REPEATED_ERROR" ? "" : "%"}</td>
    <td>${rule.enabled ? "Enabled" : "Disabled"}</td>
    <td><button class="quiet" type="button" data-rule-type="${escapeHtml(rule.rule_type)}" data-host-id="${escapeHtml(rule.host_id ?? "")}">Remove</button></td></tr>`,
      )
      .join("") ||
    '<tr><td colspan="5" class="empty">No rules stored. Every machine uses the configured defaults.</td></tr>';
  const defaults = response.defaults;
  ruleDefaults.textContent =
    `Defaults from configuration: CPU ${Number(defaults.HIGH_CPU)}% · ` +
    `RAM ${Number(defaults.HIGH_RAM)}% · repeated error ${Number(defaults.REPEATED_ERROR)}`;
}

function renderRuleHostOptions(hosts) {
  const selected = ruleHost.value;
  ruleHost.innerHTML =
    '<option value="">All machines</option>' +
    hosts
      .map(
        (host) =>
          `<option value="${escapeHtml(host.host_id)}">${escapeHtml(host.hostname ?? host.host_id)}</option>`,
      )
      .join("");
  if (selected && hosts.some((host) => host.host_id === selected)) {
    ruleHost.value = selected;
  }
}

function describeSilence(seconds) {
  const total = Number(seconds) || 0;
  if (total < 60) return `${total}s ago`;
  if (total < 3600) return `${Math.floor(total / 60)}m ago`;
  if (total < 86400) return `${Math.floor(total / 3600)}h ago`;
  return `${Math.floor(total / 86400)}d ago`;
}

function renderFleet(hosts) {
  fleetBody.innerHTML =
    hosts
      .map(
        (host) => `
    <tr><td>${escapeHtml(host.hostname ?? host.host_id)}</td>
    <td><span class="badge ${host.status === "silent" ? "badge-silent" : ""}">${escapeHtml(host.status)}</span></td>
    <td>${escapeHtml(describeSilence(host.silent_seconds))}</td>
    <td>${Number(host.event_count)}</td>
    <td class="host-tag">${escapeHtml(host.agent_version ?? "unknown")}</td>
    <td>${host.queue_depth === null || host.queue_depth === undefined ? "--" : Number(host.queue_depth)}</td>
    <td><button class="quiet" type="button" data-host="${escapeHtml(host.host_id)}">Details</button></td></tr>`,
      )
      .join("") ||
    '<tr><td colspan="7" class="empty">No machines have reported yet.</td></tr>';
}

async function showHostDetail(hostId) {
  // Toggle: a second click on the same machine closes the panel.
  if (hostDetail.dataset.host === hostId) {
    hostDetail.innerHTML = "";
    hostDetail.dataset.host = "";
    return;
  }
  const host = await getJson(`/hosts/${encodeURIComponent(hostId)}`);
  const levels = Object.entries(host.levels ?? {})
    .map(([level, count]) => `${escapeHtml(level)} ${Number(count)}`)
    .join(" · ");
  const alerts = host.alerts.length
    ? host.alerts
        .map(
          (alert) =>
            `<li>${escapeHtml(alert.alert_type)} · ${escapeHtml(alert.status)} — ${escapeHtml(alert.message)}</li>`,
        )
        .join("")
    : "<li>None active</li>";
  const rules = host.rules
    .map(
      (rule) =>
        `<li>${escapeHtml(rule.rule_type)} ${Number(rule.threshold)}` +
        `${rule.enabled ? "" : " (disabled)"} · from ${escapeHtml(rule.source)}</li>`,
    )
    .join("");

  hostDetail.dataset.host = hostId;
  hostDetail.innerHTML = `
    <h3>${escapeHtml(host.hostname ?? host.host_id)}</h3>
    <p class="alert-meta">${escapeHtml(host.host_id)} · agent ${escapeHtml(host.agent_version ?? "unknown")} ·
    queue ${host.queue_depth ?? "--"} · ${Number(host.event_count)} events ·
    first seen ${escapeHtml(new Date(host.first_seen).toLocaleString())} ·
    last seen ${escapeHtml(new Date(host.last_seen).toLocaleString())}</p>
    <p>Last ${Number(host.averages.window)} events: CPU ${Number(host.averages.cpu).toFixed(1)}% ·
    RAM ${Number(host.averages.ram).toFixed(1)}%</p>
    <p class="alert-meta">${levels || "No events"}</p>
    <div class="host-detail-columns">
      <div><strong>Active alerts</strong><ul>${alerts}</ul></div>
      <div><strong>Effective rules</strong><ul>${rules}</ul></div>
    </div>`;
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
  await request("POST", `/alerts/${encodeURIComponent(alertKey)}/${action}`);
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
  if (!signedIn) return;
  try {
    const host = hostFilter.value;
    const eventsPath = host
      ? `/events?limit=50&host_id=${encodeURIComponent(host)}`
      : "/events?limit=50";
    const [summary, events, alerts, hosts, rules] = await Promise.all([
      getJson("/summary"),
      getJson(eventsPath),
      getJson("/alerts"),
      getJson("/hosts"),
      getJson("/rules"),
    ]);
    knownHosts = hosts;
    renderHostOptions(hosts);
    renderRuleHostOptions(hosts);
    renderRules(rules);
    renderFleet(hosts);
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

function showSignedIn(state) {
  signedIn = state;
  keyInput.hidden = state;
  keyLabel.hidden = state;
  signInButton.hidden = state;
  signOutButton.hidden = !state;
}

// Signing out has to take the telemetry off the screen too: leaving the last view rendered on a
// shared machine hands it to whoever sits down next.
function clearPanels() {
  for (const id of ["#host-count", "#total-events", "#active-alerts", "#average-cpu", "#average-ram"]) {
    document.querySelector(id).textContent = "--";
  }
  eventsBody.innerHTML = "";
  alertsList.innerHTML = "";
  fleetBody.innerHTML = "";
  rulesBody.innerHTML = "";
  hostDetail.innerHTML = "";
  knownHosts = [];
}

function signOut(revoke) {
  showSignedIn(false);
  clearPanels();
  keyInput.placeholder = "EVENTWATCH_API_KEY";
  if (revoke) {
    fetch(`${apiBase}/session`, { method: "DELETE", credentials: "same-origin" }).catch(() => {});
    notice.textContent = "Signed out.";
    notice.style.borderColor = "var(--teal)";
  }
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (!keyInput.value) return;
  const key = keyInput.value;
  // Clear the field immediately: the key is needed for this one request and nothing else.
  keyInput.value = "";
  try {
    const response = await fetch(`${apiBase}/session`, {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ api_key: key }),
    });
    const body = await response.json();
    if (!response.ok) throw new Error(body.message || "Sign-in failed");
    showSignedIn(true);
    await refresh();
  } catch (error) {
    reportFailure(error);
  }
});

signOutButton.addEventListener("click", () => signOut(true));

// The cookie outlives the page, so a reload should not ask for the key again.
async function restoreSession() {
  try {
    const response = await fetch(`${apiBase}/session`, { credentials: "same-origin" });
    if (!response.ok) return;
    showSignedIn(true);
    await refresh();
  } catch {
    // No session, or the service is not reachable yet: the sign-in form is already showing.
  }
}

restoreSession();
document.querySelector("#refresh").addEventListener("click", refresh);

function reportFailure(error) {
  notice.textContent = error.message;
  notice.style.borderColor = "var(--coral)";
}

ruleForm.addEventListener("submit", (event) => {
  event.preventDefault();
  const body = {
    rule_type: document.querySelector("#rule-type").value,
    threshold: Number(document.querySelector("#rule-threshold").value),
    enabled: document.querySelector("#rule-enabled").checked,
  };
  if (ruleHost.value) body.host_id = ruleHost.value;
  sendJson("PUT", "/rules", body).then(refresh).catch(reportFailure);
});

fleetBody.addEventListener("click", (event) => {
  const button = event.target.closest("button[data-host]");
  if (!button) return;
  showHostDetail(button.dataset.host).catch(reportFailure);
});

rulesBody.addEventListener("click", (event) => {
  const button = event.target.closest("button[data-rule-type]");
  if (!button) return;
  const query = new URLSearchParams({ rule_type: button.dataset.ruleType });
  if (button.dataset.hostId) query.set("host_id", button.dataset.hostId);
  sendJson("DELETE", `/rules?${query}`).then(refresh).catch(reportFailure);
});
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
