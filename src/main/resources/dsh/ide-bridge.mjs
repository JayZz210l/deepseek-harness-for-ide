// DeepSeek Harness IDE bridge — composition-native replacement for the
// `api-gateway` row (see the JetBrains plugin, docs/architecture.md).
//
// Mounted via `dsh web --patch <patch.yml>` where the patch row is:
//
//   - id: api-gateway
//     name: '<absolute path to this file>'
//
// The JetBrains plugin sets these environment variables on the spawned
// process (this file reads them at module load; no rewriting is needed):
//
//   DSH_IDE_BRIDGE_IMPL  absolute root of the dsh installation
//                        (the directory that contains node_modules/)
//   DSH_IDE_BRIDGE_URL   http://127.0.0.1:<port> of the IDE bridge receiver
//   DSH_IDE_BRIDGE_TOKEN optional bearer token the receiver validates
//
// This module rebuilds the same ApiProxy contract the shipped
// @deepseek-ai/dsh-host-apiproxy gateway provides, but routes
// host.openPath / host.openTextFile to the IDE instead of the host desktop.
import { pathToFileURL } from 'node:url';

const implRoot = process.env.DSH_IDE_BRIDGE_IMPL;
const bridgeUrl = process.env.DSH_IDE_BRIDGE_URL;
const token = process.env.DSH_IDE_BRIDGE_TOKEN ?? '';

if (!implRoot) {
  throw new Error('DSH_IDE_BRIDGE_IMPL is not set; the JetBrains plugin sets it when spawning dsh');
}
if (!bridgeUrl) {
  throw new Error('DSH_IDE_BRIDGE_URL is not set; the JetBrains plugin sets it when spawning dsh');
}

const apiproxyUrl = pathToFileURL(
  implRoot.replace(/\\/g, '/') + '/node_modules/@deepseek-ai/dsh-host-apiproxy/lib/index.js',
).href;
const { createApiProxy } = await import(apiproxyUrl);

async function openInIde(path, signal) {
  const response = await fetch(bridgeUrl + '/open', {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      ...(token ? { authorization: 'Bearer ' + token } : {}),
    },
    body: JSON.stringify({ path }),
    signal,
  });
  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new Error(`IDE bridge ${response.status} ${text.slice(0, 200)}`);
  }
}

export default {
  inject: [
    'agentDefaultModel',
    'agents',
    'attachments',
    'directoryPicker',
    'llm',
    'sessions',
    'subagents',
    'sessionQuery',
    'tools',
    'userQuestions',
    'workspaceRegistry',
  ],
  apply(ctx, config) {
    const api = createApiProxy(ctx, {
      defaultModelSelection: () => ctx.agentDefaultModel.currentSelection(),
      saveDefaultModelSelection: (selection) => ctx.agentDefaultModel.saveSelection(selection),
      cwd: process.cwd(),
      openPath: (path, signal) => openInIde(path, signal),
      openTextFile: (path, signal) => openInIde(path, signal),
      canOpenPath: () => true,
      ...(config?.sessionExportCompressionLevel === undefined
        ? {}
        : { sessionExportCompressionLevel: config.sessionExportCompressionLevel }),
      ...(config?.coldBlankProbeMaxBytes === undefined
        ? {}
        : { coldBlankProbeMaxBytes: config.coldBlankProbeMaxBytes }),
    });
    // NOTE: the third argument of ctx.provide is the availability CHECK predicate
    // (called as `impl.check.call(...)`), NOT an "immediate" flag. Passing `true`
    // makes every evaluation throw, so all inject-dependents of apiProxy — most
    // importantly the client-connection plugin that registers the /api/events.mux
    // WebSocket downlink — never activate: the embedded UI gets no live updates
    // (streaming/thinking stays invisible until the turn completes). Omit it.
    ctx.provide(
      'apiProxy',
      {
        sessions: api.sessions,
        subagents: api.subagents,
        workspace: api.workspace,
        host: api.host,
        goals: api.goals,
        skills: api.skills,
        agentPresets: api.agentPresets,
        settings: api.settings,
        credentials: api.credentials,
        llm: api.llm,
        events: api.events,
        downloads: api.downloads,
        respond: api.respond.bind(api),
      },
    );
  },
};
