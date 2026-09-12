// Browser half of the "dsh-ide-settings" composition row: registers the
// "For IDE" section in the DeepSeek Harness settings page with plugin info,
// a real feedback button and a GitHub button. Clicks go through the host's
// openPath unary call — the IDE plugin recognizes http(s) URLs and opens
// them in the system browser. Classic-script factory form
// (window.__ModuleLoader__); tokens __PLUGIN_VERSION__ / __DSH_VERSION__ /
// __BUILD_DATE__ / __PLUGIN_ICON_BASE64__ / __FEEDBACK_URL__ / __GITHUB_URL__
// are substituted by Gradle.
window.__ModuleLoader__.load({
  id: "dsh-ide-settings",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;
    Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });
    // Defer this early patch row until the trigger registry exists.  This also
    // guarantees our @ source registers before the later stock reference row.
    exports.inject = ["inputTriggers"];
    var React = require("react");
    var primitives = require("@deepseek-ai/dsh-client-ui-primitives");
    var Button = primitives.Button;

    // The IDE may still be restoring editor tabs when the first @ menu is
    // opened. Retry a short-lived empty response so the active tab does not
    // appear only after the user switches files once.
    var cachedOpenEditorPaths = [];
    async function loadOpenEditorPaths(signal) {
      // Prefer the native JCEF channel. It reads the IDE-owned snapshot without
      // depending on DSH proxy routing, page origin, or an in-flight reconnect.
      if (typeof window.__dshIdeOpenFiles === "function") {
        try {
          var nativeValue = await window.__dshIdeOpenFiles();
          var nativePaths = typeof nativeValue === "string" ? JSON.parse(nativeValue) : nativeValue;
          if (Array.isArray(nativePaths) && nativePaths.length > 0) {
            cachedOpenEditorPaths = nativePaths;
            return nativePaths;
          }
        } catch (_nativeError) {
          // Fall through to the same-origin compatibility endpoint below.
        }
      }
      for (var attempt = 0; attempt < 4; attempt++) {
        try {
          var response = await fetch("/__dsh_ide/open-files", {
            signal: signal,
            cache: "no-store",
          });
          if (!response.ok) {
            if (attempt === 3) return cachedOpenEditorPaths;
            continue;
          }
          var paths = await response.json();
          if (Array.isArray(paths) && paths.length > 0) {
            cachedOpenEditorPaths = paths;
            return paths;
          }
          if (attempt === 3) return cachedOpenEditorPaths;
        } catch (_error) {
          if (signal && signal.aborted) return [];
          if (attempt === 3) return cachedOpenEditorPaths;
        }
        await new Promise(function (resolve) { setTimeout(resolve, 75 * (attempt + 1)); });
      }
      return [];
    }

    var INFO = {
      version: "__PLUGIN_VERSION__",
      dshVersion: "__DSH_VERSION__",
      buildDate: "__BUILD_DATE__",
      iconUrl: "data:image/png;base64,__PLUGIN_ICON_BASE64__",
      feedbackUrl: "__FEEDBACK_URL__",
      githubUrl: "__GITHUB_URL__",
      // host.openPath markers recognized by the JetBrains plugin.
      syncPluginsPath: "dsh-ide://sync-plugins",
      syncAgentPresetsPath: "dsh-ide://sync-agent-presets",
      resetPluginsPath: "dsh-ide://reset-plugins",
    };

    // Variadic createElement helper: collects ALL children arguments, so
    // h("div", props, a, b, c) renders every child (a fixed-arity version
    // silently dropped everything after the first child — the bug that left
    // the section with only its description line).
    function h(tag, props) {
      if (props === null) props = {};
      var children = Array.prototype.slice.call(arguments, 2);
      if (children.length === 0) return React.createElement(tag, props);
      if (children.length === 1) return React.createElement(tag, props, children[0]);
      return React.createElement.apply(React, [tag, props].concat(children));
    }

    exports.apply = function apply(ctx) {
      // A fast @ source backed by JetBrains' open editor tabs.  This plugin row
      // is mounted before the stock filesystem reference row, so these results
      // appear first while the original project-wide search remains available.
      var inputTriggers = ctx.get("inputTriggers");
      if (inputTriggers !== void 0) {
        var openEditorsSource = {
          trigger: "@",
          name: "ide-open-editors",
          // The stock file/session source has order 0. Use an explicit lower
          // order instead of depending on non-deterministic module load order.
          order: -1000,
          showGroupTitle: false,
          candidates: async function (_session, options) {
            var query = (options.query || "").toLowerCase();
            try {
              var paths = await loadOpenEditorPaths(options.signal);
              return paths.filter(function (path) {
                return query === "" || String(path).toLowerCase().indexOf(query) >= 0;
              }).map(function (path) {
                var text = String(path);
                var label = text.slice(text.lastIndexOf("/") + 1);
                var mention = /\s/.test(text) ? '@"' + text.replace(/"/g, "") + '"' : "@" + text;
                return {
                  name: "IDE 标签 · " + label,
                  description: text,
                  section: "已打开的文件 / Open editors",
                  value: JSON.stringify({ mention: mention, label: label }),
                };
              });
            } catch (_error) {
              return [];
            }
          },
          onPick: function (pick) {
            var value = JSON.parse(pick.candidate.value);
            return { insert: {
              source: "ide-open-editors",
              ref: value.mention,
              label: value.label,
              appearance: "file",
              clipboardText: value.mention,
            } };
          },
          codec: {
            clipboardText: function (ref) { return ref; },
            serialize: function (ref) { return Promise.resolve(ref); },
          },
        };
        ctx.effect(function () { return inputTriggers.registerSource(openEditorsSource); }, "ide: open editor @ source");
      }

      var slots = ctx.get("slots");
      if (slots === void 0) return;

      function openExternal(url) {
        // Prefer the native JCEF query installed by the JetBrains plugin. It
        // crosses directly into the IDE process, so DSH URL rewriting, service
        // workers and server routing cannot turn the command into a 404.
        if (typeof window.__dshIdeOpenPath === "function") {
          try {
            return Promise.resolve(window.__dshIdeOpenPath(url));
          } catch (error) {
            return Promise.reject(error);
          }
        }
        // DSH wraps fetch with its own API base. A relative fetch is redirected
        // to the real server port, bypassing the JetBrains proxy and returning
        // 404. Use the browser-native XHR channel and pin it to the JCEF page's
        // actual origin (the page itself is loaded from the IDE proxy).
        var bridgeUrl = window.location.origin + "/__dsh_ide/open?path=" + encodeURIComponent(url);
        return new Promise(function (resolve, reject) {
          var request = new XMLHttpRequest();
          request.open("GET", bridgeUrl, true);
          request.setRequestHeader("Cache-Control", "no-store");
          request.onload = function () {
            var body = null;
            try { body = JSON.parse(request.responseText); } catch (_error) {}
            if (request.status >= 200 && request.status < 300 && body !== null && body.opened === true) {
              resolve();
            } else {
              reject(new Error("IDE bridge returned " + request.status));
            }
          };
          request.onerror = function () { reject(new Error("IDE bridge connection failed")); };
          request.send();
        });
      }

      function ForIdeSection() {
        var state = React.useState(null);
        var actionStatus = state[0];
        var setActionStatus = state[1];
        function runAction(url) {
          setActionStatus({ tone: "progress", text: "正在发送到 IDE… / Sending to IDE…" });
          openExternal(url).then(function () {
            setActionStatus({ tone: "success", text: "操作已交给 IDE 处理 / Request accepted by IDE" });
          }).catch(function (error) {
            setActionStatus({ tone: "error", text: "操作失败 / Failed: " + error.message });
            if (typeof window !== "undefined" && /^https?:/.test(url)) window.open(url, "_blank");
          });
        }

        function VersionBadge(label, value, emphasized) {
          return h("div", { style: {
            display: "flex", alignItems: "center", gap: "7px", padding: "6px 10px",
            borderRadius: "999px", border: "1px solid var(--dsw-alias-border-l2)",
            background: emphasized ? "var(--dsw-alias-button-primary-dimmed)" : "var(--dsw-alias-bg-layer-1)",
            whiteSpace: "nowrap",
          } },
            h("span", { style: { fontSize: "11px", color: "var(--dsw-alias-label-secondary)" } }, label),
            h("span", { style: { fontSize: "12px", fontWeight: 600, color: emphasized ? "var(--dsw-alias-brand-text)" : "var(--dsw-alias-label-primary)" } }, value),
          );
        }

        function ActionCard(title, englishTitle, description, buttonText, variant, action) {
          return h("div", { style: {
            display: "flex", flexDirection: "column", minHeight: "142px", padding: "16px",
            borderRadius: "12px", border: "1px solid var(--dsw-alias-border-l2)",
            background: "var(--dsw-alias-bg-layer-1)", boxShadow: "var(--dsw-elevation-soft)",
          } },
            h("div", { style: { fontSize: "14px", lineHeight: 1.4, fontWeight: 600, color: "var(--dsw-alias-label-primary)" } }, title),
            h("div", { style: { marginTop: "2px", fontSize: "11px", color: "var(--dsw-alias-label-tertiary)" } }, englishTitle),
            h("div", { style: { flex: 1, margin: "10px 0 14px", fontSize: "12px", lineHeight: 1.55, color: "var(--dsw-alias-label-secondary)" } }, description),
            h("div", null,
              h(Button, { variant: variant, size: "sm", onClick: action }, buttonText),
            ),
          );
        }

        var statusColors = {
          progress: "var(--dsw-alias-brand-text)",
          success: "var(--dsw-alias-state-success-primary)",
          error: "var(--dsw-alias-label-error)",
        };
        return h("div", { style: { display: "flex", flexDirection: "column", gap: "18px", maxWidth: "760px", padding: "4px 0 24px" } },
          h("section", { style: {
            position: "relative", overflow: "hidden", padding: "20px", borderRadius: "14px",
            border: "1px solid var(--dsw-alias-border-l2)",
            background: "linear-gradient(135deg, var(--dsw-alias-bg-layer-2), var(--dsw-alias-bg-layer-1))",
          } },
            h("div", { style: {
              position: "absolute", width: "180px", height: "180px", right: "-72px", top: "-108px",
              borderRadius: "50%", background: "var(--dsw-alias-brand-primary)", opacity: 0.09, pointerEvents: "none",
            } }),
            h("div", { style: { position: "relative", display: "flex", alignItems: "center", gap: "13px" } },
              h("div", { style: {
                display: "grid", placeItems: "center", width: "48px", height: "48px", flex: "0 0 48px",
                borderRadius: "13px", border: "1px solid var(--dsw-alias-border-l2)",
                background: "var(--dsw-alias-bg-layer-1)", boxShadow: "var(--dsw-elevation-soft)", overflow: "hidden",
              } },
                h("img", {
                  src: INFO.iconUrl,
                  alt: "Deepseek Harness For IDE",
                  style: { display: "block", width: "42px", height: "42px", objectFit: "contain" },
                }),
              ),
              h("div", null,
                h("div", { style: { fontSize: "16px", lineHeight: 1.35, fontWeight: 650, color: "var(--dsw-alias-label-primary)" } }, "Deepseek Harness For IDE"),
                h("div", { style: { marginTop: "3px", fontSize: "12px", lineHeight: 1.45, color: "var(--dsw-alias-label-secondary)" } },
                  "让 DeepSeek Harness 与 JetBrains IDE 原生编辑、Diff 和项目工作区无缝协作。"),
              ),
            ),
            h("div", { style: { position: "relative", display: "flex", gap: "8px", flexWrap: "wrap", marginTop: "17px" } },
              VersionBadge("IDE 插件", "v" + INFO.version, false),
              VersionBadge("内置 DSH", "v" + INFO.dshVersion, true),
              VersionBadge("构建日期", INFO.buildDate, false),
            ),
          ),

          h("section", null,
            h("div", { style: { marginBottom: "10px" } },
              h("div", { style: { fontSize: "14px", fontWeight: 600, color: "var(--dsw-alias-label-primary)" } }, "项目同步"),
              h("div", { style: { marginTop: "3px", fontSize: "12px", color: "var(--dsw-alias-label-secondary)" } }, "把主 DSH 环境中的能力带到当前 IDE 项目。"),
            ),
            h("div", { style: { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(220px, 1fr))", gap: "12px" } },
              ActionCard(
                "同步 Agent 预设", "Sync agent presets",
                "复制 ~/.dsh/.agent-presets 到当前项目，立即生效，无需重启服务。",
                "同步预设", "outline", function () { runAction(INFO.syncAgentPresetsPath); }
              ),
              ActionCard(
                "同步 DSH 插件", "Sync DSH plugins",
                "同步主 DSH profile 中兼容的插件；完成后会自动重启当前项目的 DSH 服务。",
                "同步插件", "primary", function () { runAction(INFO.syncPluginsPath); }
              ),
            ),
          ),

          h("section", { style: {
            display: "flex", alignItems: "center", justifyContent: "space-between", gap: "16px", flexWrap: "wrap",
            padding: "14px 16px", borderRadius: "12px", border: "1px solid var(--dsw-alias-border-l2)",
            background: "var(--dsw-alias-bg-layer-1)",
          } },
            h("div", { style: { flex: "1 1 300px" } },
              h("div", { style: { fontSize: "13px", fontWeight: 600, color: "var(--dsw-alias-label-primary)" } }, "恢复默认插件"),
              h("div", { style: { marginTop: "3px", fontSize: "12px", lineHeight: 1.5, color: "var(--dsw-alias-label-secondary)" } },
                "清理当前项目的插件 profile 并恢复内置配置，不会修改主 DSH 目录。服务将自动重启。"),
            ),
            h(Button, { variant: "outline", size: "sm", onClick: function () { runAction(INFO.resetPluginsPath); } }, "恢复默认 / Reset"),
          ),

          actionStatus ? h("div", {
            role: "status",
            style: {
              padding: "10px 12px", borderRadius: "9px", border: "1px solid var(--dsw-alias-border-l2)",
              background: "var(--dsw-alias-bg-layer-2)", fontSize: "12px",
              color: statusColors[actionStatus.tone] || "var(--dsw-alias-label-secondary)",
            },
          }, actionStatus.text) : null,

          h("footer", { style: {
            display: "flex", alignItems: "center", justifyContent: "space-between", gap: "12px", flexWrap: "wrap",
            paddingTop: "2px", color: "var(--dsw-alias-label-secondary)", fontSize: "12px",
          } },
            h("span", null, "帮助改进 Deepseek Harness For IDE"),
            h("div", { style: { display: "flex", gap: "8px", flexWrap: "wrap" } },
              h(Button, { variant: "ghost", size: "sm", onClick: function () { runAction(INFO.feedbackUrl); } }, "反馈问题"),
              h(Button, { variant: "ghost", size: "sm", onClick: function () { runAction(INFO.githubUrl); } }, "GitHub"),
            ),
          ),
        );
      }

      slots.inject("settings.section", function () {
        return slots.register(
          { name: "settings.section", id: "for-ide", order: 900, label: "For IDE" },
          function () { return h(ForIdeSection, null); },
        );
      });
    };
    return module.exports;
  }
});
