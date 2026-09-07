// Browser half of the "dsh-ide-settings" composition row: registers the
// "For IDE" section in the DeepSeek Harness settings page with plugin info,
// a real feedback button and a GitHub button. Clicks go through the host's
// openPath unary call — the IDE plugin recognizes http(s) URLs and opens
// them in the system browser. Classic-script factory form
// (window.__ModuleLoader__); tokens __PLUGIN_VERSION__ / __BUILD_DATE__ /
// __FEEDBACK_URL__ / __GITHUB_URL__ are substituted by the Gradle build.
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
    async function loadOpenEditorPaths(signal) {
      for (var attempt = 0; attempt < 4; attempt++) {
        try {
          var response = await fetch("/__dsh_ide/open-files", {
            signal: signal,
            cache: "no-store",
          });
          if (!response.ok) return [];
          var paths = await response.json();
          if (Array.isArray(paths) && (paths.length > 0 || attempt === 3)) return paths;
        } catch (_error) {
          if (signal && signal.aborted) return [];
          if (attempt === 3) return [];
        }
        await new Promise(function (resolve) { setTimeout(resolve, 75 * (attempt + 1)); });
      }
      return [];
    }

    var INFO = {
      version: "__PLUGIN_VERSION__",
      buildDate: "__BUILD_DATE__",
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
        var state = React.useState("");
        var actionStatus = state[0];
        var setActionStatus = state[1];
        function runAction(url) {
          setActionStatus("正在发送到 IDE… / Sending to IDE…");
          openExternal(url).then(function () {
            setActionStatus("操作已交给 IDE 处理 / Request accepted by IDE");
          }).catch(function (error) {
            setActionStatus("操作失败 / Failed: " + error.message);
            if (typeof window !== "undefined" && /^https?:/.test(url)) window.open(url, "_blank");
          });
        }
        var rows = [
          ["插件版本 Version", INFO.version],
          ["构建日期 Build date", INFO.buildDate],
        ];
        return h("div", { style: { display: "flex", flexDirection: "column", gap: "12px", padding: "4px 0" } },
          h("p", { style: { margin: 0, fontSize: "13px" } },
            "Deepseek Harness For IDE —— 把 DeepSeek Harness 嵌入 JetBrains IDE 的插件。"),
          h("p", { style: { margin: 0, fontSize: "12px", color: "var(--dsw-alias-label-secondary)" } },
            "同步预设：把 ~/.dsh/.agent-presets 的预设复制到当前项目，无需重启。同步插件 / 恢复默认插件需要重启 DSH 服务，期间界面暂时不可用属于正常现象，请耐心等待。"),
          h("div", { style: { display: "flex", flexDirection: "column", gap: "6px" } },
            rows.map(function (row) {
              return h("div", { key: row[0], style: { display: "flex", gap: "12px", fontSize: "13px" } },
                h("span", { style: { color: "var(--dsw-alias-label-secondary)", minWidth: "150px" } }, row[0]),
                h("span", null, row[1]),
              );
            }),
          ),
          h("div", { style: { display: "flex", gap: "10px", paddingTop: "4px", flexWrap: "wrap" } },
            h(Button, { variant: "outline", size: "sm", onClick: function () { runAction(INFO.syncAgentPresetsPath); } },
              "同步预设 / Sync presets"),
            h(Button, { variant: "outline", size: "sm", onClick: function () { runAction(INFO.syncPluginsPath); } },
              "同步插件 / Sync plugins"),
            h(Button, { variant: "ghost", size: "sm", onClick: function () { runAction(INFO.resetPluginsPath); } },
              "恢复默认插件 / Reset plugins"),
            h(Button, { variant: "outline", size: "sm", onClick: function () { runAction(INFO.feedbackUrl); } },
              "反馈 BUG / Report a problem"),
            h(Button, { variant: "ghost", size: "sm", onClick: function () { runAction(INFO.githubUrl); } },
              "GitHub"),
          ),
          actionStatus ? h("div", {
            role: "status",
            style: { fontSize: "12px", color: "var(--dsw-alias-label-secondary)" },
          }, actionStatus) : null,
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
