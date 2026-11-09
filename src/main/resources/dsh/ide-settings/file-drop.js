// JCEF exposes native paths through the IDE query, while browser File objects
// deliberately omit them. Feed those paths to DSH's desktop file-drop contract.
// Install before the composer mounts: it retains this bridge object.
(() => {
  const paths = new WeakMap();
  const previous = globalThis.__DSH_HOST_PATHS__;
  globalThis.__DSH_HOST_PATHS__ = {
    pathFor(file) {
      return paths.get(file) ?? previous?.pathFor(file) ?? "";
    },
  };
  globalThis.__dshIdePrepareFileDrop = async (files, directories) => {
    const drop = { files, directories: new Set(directories) };
    if (typeof globalThis.__dshIdeDroppedFiles !== "function") return drop;
    try {
      const response = await globalThis.__dshIdeDroppedFiles();
      const entries = typeof response === "string" ? JSON.parse(response) : response;
      if (!Array.isArray(entries) || entries.length !== files.length) return drop;
      // Validate the entire batch before assigning paths. A canceled drag must
      // never supply a previous file's path to a different browser File.
      const nameOf = (path) => path.replace(/\\/g, "/").split("/").pop();
      if (!entries.every((entry, index) => typeof entry.path === "string" && nameOf(entry.path) === files[index].name)) return drop;
      entries.forEach((entry, index) => {
        paths.set(files[index], entry.path);
        if (entry.directory) drop.directories.add(files[index]);
      });
    } catch (error) {
      console.warn("DeepSeek Harness IDE could not resolve dropped file paths", error);
    }
    return drop;
  };
})();
