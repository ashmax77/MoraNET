const state = { sessionId: null, eventSource: null };

const $ = (id) => document.getElementById(id);
const titles = {
  overview: "Operations Overview", chat: "Chat Operations",
  files: "File Transfer", network: "Discovery & Network",
  config: "Backend Configuration", console: "Protocol Console"
};

function logProtocol(direction, text) {
  const line = document.createElement("div");
  line.className = "console-line";
  line.innerHTML = `<span class="${direction}">${direction === "send" ? "TX" : "RX"}</span>  ${escapeHtml(text)}`;
  $("consoleOutput").appendChild(line);
  $("consoleOutput").scrollTop = $("consoleOutput").scrollHeight;
}

function toast(text) {
  $("toast").textContent = text;
  $("toast").classList.add("show");
  setTimeout(() => $("toast").classList.remove("show"), 2800);
}

function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, char => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  }[char]));
}

$("roleSelect").addEventListener("change", () => {
  const role = $("roleSelect").value;
  if (role === "STUDENT") {
    $("loginUser").value = "alice";
    $("loginPass").value = "pw1";
  } else if (role === "LECTURER") {
    $("loginUser").value = "bob";
    $("loginPass").value = "pw2";
  } else {
    $("loginUser").value = "partner";
    $("loginPass").value = "pw3";
  }
});

async function api(path, options = {}) {
  const response = await fetch(path, options);
  const type = response.headers.get("content-type") || "";
  const body = type.includes("application/json") ? await response.json() : await response.text();
  if (!response.ok) throw new Error(body.message || body.error || `HTTP ${response.status}`);
  return body;
}

function addChatMessage(text, kind = "server") {
  const item = document.createElement("div");
  item.className = `chat-message ${kind}`;
  item.textContent = text;
  $("chatMessages").appendChild(item);
  $("chatMessages").scrollTop = $("chatMessages").scrollHeight;
}

async function refreshHealth() {
  try {
    const health = await api("/api/health");
    $("sidebarStatus").textContent = "All systems live";
    $("m1Status").textContent = "Chat port " + health.chat.port;
    $("m2Status").textContent = "Client bridge ready";
    $("m3Status").textContent = "UDP port " + health.discovery.port;
    $("m4Status").textContent = "File port " + health.file.port;
    $("m5Status").textContent = "Auth + token active";
    $("fileStatus").textContent = "TCP " + health.file.port;
    $("metrics").innerHTML = [
      ["Chat", health.chat.port],
      ["File", health.file.port],
      ["Discovery", health.discovery.port],
      ["Dashboard", health.dashboard.port]
    ].map(([label, value]) => `<article class="metric"><span>${label}</span><strong>${value}</strong></article>`).join("");
  } catch (error) {
    $("sidebarStatus").textContent = "Backend unavailable";
    toast(error.message);
  }
}

async function refreshConfig() {
  const data = await api("/api/config");
  $("configTable").innerHTML = Object.entries(data.values).map(([key, value]) =>
    `<div class="config-row"><span>${escapeHtml(key)}</span><strong>${escapeHtml(String(value))}</strong></div>`
  ).join("");
}

async function refreshFiles() {
  try {
    const data = await api("/api/files");
    $("fileList").innerHTML = data.files.length ? data.files.map(name =>
      `<div class="file-row"><span>${escapeHtml(name)}</span><button data-download="${escapeHtml(name)}">Download</button></div>`
    ).join("") : '<div class="file-row"><span>No uploaded files yet</span></div>';
  } catch (error) {
    toast(error.message);
  }
}

async function connectChat() {
  try {
    if (state.sessionId) await api(`/api/chat/sessions/${state.sessionId}`, { method: "DELETE" });
    const created = await api("/api/chat/sessions", { method: "POST" });
    state.sessionId = created.id;
    $("chatStatus").textContent = "Connected";
    addChatMessage("WELCOME campusnet/1", "server");

    if (state.eventSource) state.eventSource.close();
    state.eventSource = new EventSource(`/api/chat/sessions/${state.sessionId}/events`);
    state.eventSource.onmessage = (event) => {
      let frame;
      try { frame = JSON.parse(event.data); } catch { frame = event.data; }
      addChatMessage(frame, "server");
      logProtocol("receive", frame);
    };

    const user = $("loginUser").value.trim();
    const pass = $("loginPass").value;
    await sendFrame(`AUTH ${user} ${pass}`);
  } catch (error) {
    toast(error.message);
  }
}

async function sendFrame(frame) {
  if (!state.sessionId) {
    toast("Connect to chat first");
    return;
  }
  logProtocol("send", frame);
  try {
    const data = await api(`/api/chat/sessions/${state.sessionId}/frames`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ frame })
    });
    data.responses.forEach(response => {
      addChatMessage(response, "server");
      logProtocol("receive", response);
    });
  } catch (error) {
    toast(error.message);
  }
}

async function discover() {
  $("discoveryResult").textContent = "Searching UDP broadcast...";
  try {
    const data = await api("/api/discovery", { method: "POST" });
    $("discoveryResult").textContent = data.found
      ? JSON.stringify(data, null, 2)
      : "No MoraNET server found within the discovery window.";
  } catch (error) {
    $("discoveryResult").textContent = error.message;
  }
}

async function inspectNetInfo() {
  $("netInfo").textContent = "Inspecting interfaces...";
  try {
    const data = await api("/api/netinfo");
    $("netInfo").textContent = data.text;
  } catch (error) {
    $("netInfo").textContent = error.message;
  }
}

async function uploadFile() {
  if (!state.sessionId) return toast("Connect and login first");
  const input = $("fileInput");
  if (!input.files.length) return toast("Choose a file");
  const form = new FormData();
  form.append("sessionId", state.sessionId);
  form.append("file", input.files[0]);
  try {
    const data = await api("/api/files/upload", { method: "POST", body: form });
    logProtocol("receive", `UPLOAD ${data.name} ${data.size} bytes`);
    toast("Upload complete");
    refreshFiles();
  } catch (error) {
    logProtocol("receive", `ERR ${error.message}`);
    toast(error.message);
  }
}

async function downloadFile(name) {
  if (!state.sessionId) return toast("Connect and login first");
  try {
    const response = await fetch(`/api/files/${encodeURIComponent(name)}?sessionId=${encodeURIComponent(state.sessionId)}`);
    if (!response.ok) throw new Error((await response.json()).error || "Download failed");
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = name;
    link.click();
    URL.revokeObjectURL(url);
    toast("Download complete");
  } catch (error) {
    toast(error.message);
  }
}

async function runDemo() {
  toast("Running complete demo...");
  try {
    const data = await api("/api/demo/run", { method: "POST" });
    logProtocol("receive", "DEMO " + data.steps.join(" -> "));
    await discover();
    await refreshFiles();
    toast("Demo run ready");
  } catch (error) {
    toast(error.message);
  }
}

document.querySelectorAll(".nav-item").forEach(button => {
  button.addEventListener("click", () => {
    document.querySelectorAll(".nav-item").forEach(item => item.classList.remove("active"));
    document.querySelectorAll(".view").forEach(view => view.classList.remove("active"));
    button.classList.add("active");
    $(button.dataset.view).classList.add("active");
    $("pageTitle").textContent = titles[button.dataset.view];
  });
});

$("refreshBtn").addEventListener("click", refreshHealth);
$("connectChatBtn").addEventListener("click", connectChat);
$("sendChatBtn").addEventListener("click", () => {
  const input = $("chatInput");
  if (!input.value.trim()) return;
  sendFrame(input.value.trim());
  input.value = "";
});
$("chatInput").addEventListener("keydown", event => {
  if (event.key === "Enter") $("sendChatBtn").click();
});
document.querySelectorAll("[data-frame]").forEach(button =>
  button.addEventListener("click", () => sendFrame(button.dataset.frame)));
$("privateSendBtn").addEventListener("click", () =>
  sendFrame(`PRIVATE ${$("privateUser").value.trim()} ${$("privateText").value.trim()}`));
$("discoverBtn").addEventListener("click", discover);
$("netInfoBtn").addEventListener("click", inspectNetInfo);
$("refreshConfigBtn").addEventListener("click", refreshConfig);
$("refreshFilesBtn").addEventListener("click", refreshFiles);
$("uploadBtn").addEventListener("click", uploadFile);
$("downloadBtn").addEventListener("click", () => downloadFile($("downloadName").value.trim()));
$("fileList").addEventListener("click", event => {
  const button = event.target.closest("[data-download]");
  if (button) downloadFile(button.dataset.download);
});
$("clearConsoleBtn").addEventListener("click", () => $("consoleOutput").textContent = "");
$("runDemoBtn").addEventListener("click", runDemo);

setInterval(() => {
  $("clock").textContent = new Date().toLocaleTimeString([], {
    hour: "2-digit", minute: "2-digit"
  });
}, 1000);

refreshHealth();
refreshConfig();
refreshFiles();
