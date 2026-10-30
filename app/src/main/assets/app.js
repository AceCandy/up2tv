"use strict";
const byId = (id) => document.getElementById(id);
const maxBytes = 1024 * 1024 * 1024;
let token = /^#[a-f0-9]{48}$/.test(location.hash) ? location.hash.slice(1) : "";
let request = null;
if (location.hash) history.replaceState(null, "", location.pathname);

function message(id, text, error = false) {
  byId(id).textContent = text;
  byId(id).classList.toggle("error", error);
}

function connected() {
  byId("pair-form").hidden = Boolean(token);
  byId("upload-form").hidden = !token;
  byId("reconnect").hidden = true;
}
connected();

byId("pair-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  byId("pair-button").disabled = true;
  message("pair-status", "正在连接电视…");
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch("/pair", {
      method: "POST", headers: { "X-Up2TV-Pin": byId("pin").value.trim() },
      signal: controller.signal,
    });
    const text = await response.text();
    if (!response.ok) throw new Error(text);
    if (!/^[a-f0-9]{48}$/.test(text)) throw new Error("电视返回了无效连接信息，请重新开启接收。");
    token = text;
    byId("pin").value = "";
    connected();
    byId("apk").focus();
  } catch (error) {
    message("pair-status", error instanceof TypeError || error.name === "AbortError"
      ? "无法连接电视。请检查网络，并确认电视仍在接收页面。" : error.message, true);
  } finally {
    clearTimeout(timeout);
    byId("pair-button").disabled = false;
  }
});

function selected() {
  const file = byId("apk").files[0];
  const valid = file && /\.apk$/i.test(file.name) && file.size > 0 && file.size <= maxBytes;
  byId("upload-button").disabled = !valid;
  byId("file-info").hidden = !file;
  byId("file-info").textContent = file
    ? `${file.name} · ${(file.size / 1024 / 1024).toFixed(1)} MiB` : "";
  message("upload-status", file && !valid ? "请选择非空且不超过 1 GiB 的 .apk 文件。" : "", Boolean(file && !valid));
  return valid;
}
byId("apk").addEventListener("change", selected);

byId("upload-form").addEventListener("submit", (event) => {
  event.preventDefault();
  if (request || !token || !selected()) return;
  const file = byId("apk").files[0];
  const xhr = new XMLHttpRequest();
  request = xhr;
  byId("apk").disabled = true;
  byId("upload-button").disabled = true;
  byId("upload-button").textContent = "正在上传…";
  byId("cancel-button").hidden = false;
  byId("transfer").hidden = false;
  byId("progress").value = 0;
  byId("progress-label").textContent = "上传进度 0%";
  byId("reconnect").hidden = true;
  message("upload-status", "上传中，请保持页面打开。");
  xhr.open("POST", "/upload");
  xhr.timeout = 30 * 60 * 1000;
  xhr.setRequestHeader("Content-Type", "application/octet-stream");
  xhr.setRequestHeader("X-Up2TV-Token", token);
  xhr.setRequestHeader("X-File-Name", encodeURIComponent(file.name));
  xhr.upload.onprogress = (progress) => {
    if (!progress.lengthComputable) return;
    const percent = Math.floor(progress.loaded / progress.total * 100);
    byId("progress").value = percent;
    byId("progress-label").textContent = `上传进度 ${percent}%`;
    if (percent === 100) message("upload-status", "文件已发送，正在等待电视检查安装包…");
  };
  xhr.onload = () => {
    const success = xhr.status === 200;
    message("upload-status", xhr.responseText || "电视未返回结果，请查看电视屏幕。", !success);
    if (success) {
      byId("apk").value = "";
      byId("file-info").textContent = "可继续选择下一个 APK。";
      byId("progress").value = 100;
    } else if (xhr.status === 401 || xhr.status === 403) byId("reconnect").hidden = false;
  };
  xhr.onerror = () => {
    message("upload-status", "连接中断，未能确认上传结果。请查看电视，并检查网络后重试。", true);
    byId("reconnect").hidden = false;
  };
  xhr.ontimeout = () => {
    message("upload-status", "上传超时，请查看电视状态后重试。", true);
    byId("reconnect").hidden = false;
  };
  xhr.onabort = () => message("upload-status", "已取消传输。若文件已发送完成，请在电视上检查并删除。");
  xhr.onloadend = () => {
    request = null;
    byId("apk").disabled = false;
    byId("upload-button").disabled = !byId("apk").files.length;
    byId("upload-button").textContent = "上传到电视";
    byId("cancel-button").hidden = true;
  };
  xhr.send(file);
});
byId("cancel-button").addEventListener("click", () => request?.abort());
byId("reconnect").addEventListener("click", () => {
  token = "";
  connected();
  message("pair-status", "请使用电视当前显示的地址和连接码；若地址变化，请重新扫码。");
  byId("pin").focus();
});
window.addEventListener("beforeunload", (event) => {
  if (request) {
    event.preventDefault();
    event.returnValue = "";
  }
});
