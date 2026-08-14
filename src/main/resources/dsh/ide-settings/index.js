// Host half of the "dsh-ide-settings" composition row: a no-op that exists so
// the row imports cleanly on the host. The browser half (client.js) registers
// the "For IDE" section in the DeepSeek Harness settings page.
export default { name: 'dsh-ide-settings', apply() {} };
