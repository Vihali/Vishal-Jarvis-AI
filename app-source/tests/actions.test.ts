/**
 * Regression tests for the deterministic command parser and the tool schema that
 * text chat and Live voice share. Run with `npm test`.
 */
import assert from 'node:assert/strict';
import { LIVE_TOOLS, MOBILE_TOOL, REMEMBER_TOOL, describeAction, parseMobileAction } from '../src/mobile/actions';

type Case = [input: string, expected: ReturnType<typeof parseMobileAction>];
const cases: Case[] = [
  ['torch on', { action: 'set_flashlight', enabled: true }],
  ['টর্চ বন্ধ করো', { action: 'set_flashlight', enabled: false }],
  ['turn off the flashlight', { action: 'set_flashlight', enabled: false }],
  ['wifi', { action: 'open_wifi_settings' }],
  ['আলার্ম 7:30', { action: 'set_alarm', hour: 7, minute: 30 }],
  ['৭টায় অ্যালার্ম দাও', { action: 'set_alarm', hour: 7, minute: 0 }],
  ['set an alarm for 9 pm', { action: 'set_alarm', hour: 21, minute: 0 }],
  ['wake me up at 6:15 am', { action: 'set_alarm', hour: 6, minute: 15 }],
  ['timer 5 minutes', { action: 'set_timer', seconds: 300 }],
  ['2 hours timer', { action: 'set_timer', seconds: 7200 }],
  ['২ ঘণ্টা টাইমার', { action: 'set_timer', seconds: 7200 }],
  ['৫ মিনিটের টাইমার', { action: 'set_timer', seconds: 300 }],
  ['volume 40', { action: 'set_volume', level: 40 }],
  ['volume down', { action: 'set_volume', direction: 'down' }],
  ['next song', { action: 'media_control', command: 'next' }],
  ['open https://example.com/a?b=1', { action: 'open_url', url: 'https://example.com/a?b=1' }],
  ['call +919876543210', { action: 'call_number', phone: '+919876543210' }],
  ['রাহুল কে কল করো', { action: 'call_contact', recipient: 'রাহুল' }],
  ['open whatsapp', { action: 'open_app', app: 'whatsapp' }],
  ['youtube search cricket highlights', { action: 'search_youtube', query: 'cricket highlights' }],
  ['message Rahul on whatsapp: কেমন আছো?', { action: 'whatsapp_message', recipient: 'Rahul', message: 'কেমন আছো?' }],
  ['device status', { action: 'device_status' }],
  ['open camera', { action: 'open_camera' }],
  ['hello there', null],
  ['call', null],
  ['search the web for ', null],
  ['open', null],
  ['set a reminder', null],
];

let failed = 0;
for (const [input, expected] of cases) {
  const actual = parseMobileAction(input);
  try {
    assert.deepEqual(actual, expected);
  } catch {
    failed++;
    console.error(`FAIL parseMobileAction(${JSON.stringify(input)})\n  expected ${JSON.stringify(expected)}\n  actual   ${JSON.stringify(actual)}`);
  }
}

// The parser must never invent a device action from ordinary prose.
for (const prose of ['আজ আবহাওয়া কেমন?', 'how are you', 'tell me a joke about my phone', 'what is 2 + 2']) {
  if (parseMobileAction(prose) !== null) {
    failed++;
    console.error(`FAIL prose became an action: ${prose}`);
  }
}

const declared = MOBILE_TOOL.parameters.properties.action.enum as string[];
const javaMirror = ['open_app', 'open_url', 'search_youtube', 'search_web', 'whatsapp_message', 'send_sms', 'call_number',
  'call_contact', 'set_flashlight', 'open_wifi_settings', 'open_settings_panel', 'set_alarm', 'set_timer', 'create_event',
  'navigate_to', 'share_text', 'copy_text', 'media_control', 'set_volume', 'device_status', 'list_apps', 'open_camera'];
if (JSON.stringify(declared) !== JSON.stringify(javaMirror)) {
  failed++;
  console.error('FAIL mobile_action enum drifted from MobileTools.java');
}
assert.equal(LIVE_TOOLS.length, 2);
assert.equal(REMEMBER_TOOL.parameters.required?.[0], 'fact');
assert.match(describeAction('set_alarm', { hour: 7, minute: 5 }), /07:05/);
assert.equal(describeAction('unknown_action', {}), 'Working on your phone');

console.log(`${cases.length + 4} parser cases checked, ${declared.length} actions declared, ${failed} failure(s)`);
process.exit(failed === 0 ? 0 : 1);
