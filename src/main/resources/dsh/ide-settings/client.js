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
          showGroupTitle: false,
          candidates: async function (_session, options) {
            var query = (options.query || "").toLowerCase();
            try {
              var response = await fetch("/__dsh_ide/open-files", { signal: options.signal });
              if (!response.ok) return [];
              var paths = await response.json();
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
        var connection = ctx.get("connection");
        if (connection !== void 0 && connection.api !== void 0 && connection.api.host !== void 0) {
          try {
            var pending = connection.api.host.openPath({ path: url });
            if (pending !== void 0 && typeof pending.catch === "function") pending.catch(function () {});
            return;
          } catch (err) { /* fall through to window.open */ }
        }
        if (typeof window !== "undefined") window.open(url, "_blank");
      }

      function ForIdeSection() {
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
            h(Button, { variant: "outline", size: "sm", onClick: function () { openExternal(INFO.syncAgentPresetsPath); } },
              "同步预设 / Sync presets"),
            h(Button, { variant: "outline", size: "sm", onClick: function () { openExternal(INFO.syncPluginsPath); } },
              "同步插件 / Sync plugins"),
            h(Button, { variant: "ghost", size: "sm", onClick: function () { openExternal(INFO.resetPluginsPath); } },
              "恢复默认插件 / Reset plugins"),
            h(Button, { variant: "outline", size: "sm", onClick: function () { openExternal(INFO.feedbackUrl); } },
              "反馈 BUG / Report a problem"),
            h(Button, { variant: "ghost", size: "sm", onClick: function () { openExternal(INFO.githubUrl); } },
              "GitHub"),
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
