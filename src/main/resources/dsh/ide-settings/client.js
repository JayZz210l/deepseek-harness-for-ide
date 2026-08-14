// Browser half of the "dsh-ide-settings" composition row: registers the
// "For IDE" section in the DeepSeek Harness settings page with plugin info
// and a bug-report link. Classic-script factory form (window.__ModuleLoader__),
// tokens __PLUGIN_VERSION__ / __BUILD_DATE__ / __FEEDBACK_URL__ / __GITHUB_URL__
// are substituted by the Gradle build when bundling the DSH runtime.
window.__ModuleLoader__.load({
  id: "dsh-ide-settings",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;
    Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });
    var React = require("react");

    var INFO = {
      version: "__PLUGIN_VERSION__",
      buildDate: "__BUILD_DATE__",
      feedbackUrl: "__FEEDBACK_URL__",
      githubUrl: "__GITHUB_URL__",
    };

    function h(tag, props, children) {
      if (props === null) props = {};
      return React.createElement(tag, props, children);
    }

    function ForIdeSection() {
      var rows = [
        ["插件版本 Version", INFO.version],
        ["构建日期 Build date", INFO.buildDate],
      ];
      return h("div", { style: { display: "flex", flexDirection: "column", gap: "10px", padding: "4px 0" } },
        h("p", { style: { margin: 0, fontSize: "13px" } },
          "Deepseek Harness For IDE —— 把 DeepSeek Harness 嵌入 JetBrains IDE 的插件。"),
        h("div", { style: { display: "flex", flexDirection: "column", gap: "6px" } },
          rows.map(function (row) {
            return h("div", { key: row[0], style: { display: "flex", gap: "12px", fontSize: "13px" } },
              h("span", { style: { color: "var(--dsw-alias-label-secondary)", minWidth: "150px" } }, row[0]),
              h("span", null, row[1]),
            );
          }),
        ),
        h("div", { style: { display: "flex", gap: "16px", paddingTop: "4px" } },
          h("a", { href: INFO.feedbackUrl, target: "_blank", rel: "noreferrer", style: { fontSize: "13px" } },
            "反馈 BUG / Report a problem"),
          h("a", { href: INFO.githubUrl, target: "_blank", rel: "noreferrer", style: { fontSize: "13px" } },
            "GitHub"),
        ),
      );
    }

    exports.apply = function apply(ctx) {
      var slots = ctx.get("slots");
      if (slots === void 0) return;
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
