export type MobileAction =
  | { action: 'open_app'; app: string }
  | { action: 'open_url'; url: string }
  | { action: 'search_youtube' | 'search_web'; query: string }
  | { action: 'whatsapp_message'; recipient: string; message: string }
  | { action: 'send_sms'; recipient: string; message: string }
  | { action: 'call_number'; phone: string }
  | { action: 'call_contact'; recipient: string }
  | { action: 'set_flashlight'; enabled: boolean }
  | { action: 'open_wifi_settings' }
  | { action: 'open_settings_panel'; panel: string }
  | { action: 'set_alarm'; hour: number; minute: number; label?: string }
  | { action: 'set_timer'; seconds: number; label?: string }
  | { action: 'navigate_to'; destination: string }
  | { action: 'media_control'; command: 'play_pause' | 'next' | 'previous' }
  | { action: 'set_volume'; level?: number; direction?: 'up' | 'down' }
  | { action: 'device_status' }
  | { action: 'open_camera' };

const ACTION_ENUM = ['open_app', 'open_url', 'search_youtube', 'search_web', 'whatsapp_message', 'send_sms',
  'call_number', 'call_contact', 'set_flashlight', 'open_wifi_settings', 'open_settings_panel', 'set_alarm',
  'set_timer', 'create_event', 'navigate_to', 'share_text', 'copy_text', 'media_control', 'set_volume',
  'device_status', 'list_apps', 'open_camera'];

/** Mirrors MobileTools.java so text chat and Live voice share one Android capability set. */
export const MOBILE_TOOL = {
  name: 'mobile_action',
  description: 'Work the user\'s Android phone through supported, reversible, user-confirmed actions: open installed apps, open an https page, search YouTube or the web, prepare WhatsApp or SMS drafts, dial a number or saved contact, toggle the flashlight, open Wi-Fi and system panels, set alarms and timers, open a calendar event editor, open Maps navigation, share text, copy to the clipboard, send media keys, change media volume, read device status, list installed apps, open the camera. Contacts are resolved locally on the device. Messages are drafts the user sends. Never invent success: report the returned status truthfully.',
  parameters: { type: 'OBJECT', properties: {
    action: { type: 'STRING', enum: ACTION_ENUM, description: 'Which Android action to perform.' },
    app: { type: 'STRING', description: 'App name as shown on the home screen, such as WhatsApp, YouTube, Chrome, Calculator. Only for open_app.' },
    url: { type: 'STRING', description: 'A complete https:// page address explicitly given or implied by the user. Only for open_url.' },
    query: { type: 'STRING', description: 'The exact search text the user asked for. Required for search_youtube and search_web.' },
    recipient: { type: 'STRING', description: 'Saved contact name exactly as the user said it, or a full international phone number starting with + and country code. Contacts are resolved locally. Used by whatsapp_message, send_sms and call_contact.' },
    message: { type: 'STRING', description: 'The complete message body the user asked to write. Ask for it if missing. Used by whatsapp_message and send_sms.' },
    phone: { type: 'STRING', description: 'A phone number the user said explicitly, including country code. Only for call_number.' },
    enabled: { type: 'BOOLEAN', description: 'true turns the flashlight on, false turns it off. Only for set_flashlight.' },
    panel: { type: 'STRING', enum: ['wifi', 'bluetooth', 'sound', 'display', 'battery', 'location', 'apps', 'notifications', 'data', 'airplane', 'accessibility', 'nfc', 'home'], description: 'Which system settings panel to open. Only for open_settings_panel.' },
    hour: { type: 'INTEGER', description: 'Alarm hour on a 24-hour clock, 0-23. Only for set_alarm.' },
    minute: { type: 'INTEGER', description: 'Alarm minute, 0-59. Only for set_alarm.' },
    seconds: { type: 'INTEGER', description: 'Timer length in seconds, 1-86400. Only for set_timer.' },
    label: { type: 'STRING', description: 'Short label for an alarm or timer.' },
    title: { type: 'STRING', description: 'Calendar event title. Only for create_event.' },
    beginTime: { type: 'NUMBER', description: 'Event start as Unix epoch milliseconds in the device time zone. Only for create_event.' },
    endTime: { type: 'NUMBER', description: 'Event end as Unix epoch milliseconds. Defaults to one hour after the start. Only for create_event.' },
    location: { type: 'STRING', description: 'Optional place for a calendar event.' },
    description: { type: 'STRING', description: 'Optional notes for a calendar event.' },
    destination: { type: 'STRING', description: 'Place or address to show in Maps. Only for navigate_to.' },
    text: { type: 'STRING', description: 'Text to share or copy. Only for share_text and copy_text.' },
    command: { type: 'STRING', enum: ['play_pause', 'next', 'previous'], description: 'Media key to send. Only for media_control.' },
    level: { type: 'INTEGER', description: 'Target media volume as a percentage, 0-100. Only for set_volume.' },
    direction: { type: 'STRING', enum: ['up', 'down'], description: 'Raise or lower media volume by one step. Only for set_volume when level is absent.' },
  }, required: ['action'] },
};

export const REMEMBER_TOOL = {
  name: 'remember_fact',
  description: 'Save one durable fact or preference about the user so future conversations can use it, for example their name, city, favourite language, or a standing routine. Only call this when the user asks you to remember something or states a lasting preference. Never store API keys, passwords, OTPs, card numbers or other secrets. Keep the fact under 240 characters, in the user\'s own words.',
  parameters: { type: 'OBJECT', properties: {
    fact: { type: 'STRING', description: 'Short, self-contained fact to remember. One sentence.' },
  }, required: ['fact'] },
};

export const LIVE_TOOLS = [MOBILE_TOOL, REMEMBER_TOOL];

/** Exact local typed commands; Live voice and text chat use structured Gemini function calls. */
export function parseMobileAction(input: string): MobileAction | null {
  const digits = (value: string) => value.replace(/[০-৯]/g, d => String('০১২৩৪৫৬৭৮৯'.indexOf(d)));
  const text = digits(input.trim()).replace(/^(?:hey\s+)?(?:vishal\s+jarvis|vishal|roisal\s+jarvis|roisal|jarvis|myraa|myra|মায়া|মায়া)[,\s:]+/iu, '').replace(/^(?:please|can you|could you)\s+/i, '').trim();
  let call = /^(?:কল\s*(?:করো|কর|দাও)?|ফোন\s*করো|call(?:\s+to)?|dial)\s*[:,ঃ-]?\s*(?:এই\s+নম্বর(?:ে)?\s*)?([+0-9][0-9 ()-]{6,22})$/iu.exec(text) || /^([+0-9][0-9 ()-]{6,22})\s*(?:নম্বরে\s*)?(?:কল\s*করো|ফোন\s*করো)$/iu.exec(text);
  if (call) return { action: 'call_number', phone: call[1].trim() };
  if (/^(?:(?:ওয়াই|ওয়াই).?ফাই|wifi|wi-fi|ইন্টারনেট)(?:\s*(?:অন|চালু|on|enable))?\s*(?:করো|চালু করো|খোলো|open)?$/iu.test(text) || /^(?:(?:ওয়াই|ওয়াই).?ফাই|wifi|wi-fi)\s*(?:সেটিংস|settings)\s*(?:খোলো|open)?$/iu.test(text) || /^(?:turn\s+on\s+)?wi-?fi\s+(?:on|enable)$/i.test(text)) return { action: 'open_wifi_settings' };
  if (/^(?:ফ্ল্যাশ\s*লাইট|ফ্ল্যাশলাইট|টর্চ|torch|flashlight)\s*(?:অন|চালু|on|ওপেন|খোলো|open)(?:\s*করো)?$/iu.test(text) || /^(?:অন|চালু|ওপেন|open)\s*(?:করো\s*)?(?:ফ্ল্যাশ\s*লাইট|ফ্ল্যাশলাইট|টর্চ|torch|flashlight)$/iu.test(text) || /^turn\s+on\s+(?:the\s+)?flashlight$/i.test(text)) return { action: 'set_flashlight', enabled: true };
  if (/^(?:ফ্ল্যাশ\s*লাইট|ফ্ল্যাশলাইট|টর্চ|torch|flashlight)\s*(?:অফ|বন্ধ|off)(?:\s*করো)?$/iu.test(text) || /^(?:বন্ধ|অফ)\s*(?:করো\s*)?(?:ফ্ল্যাশ\s*লাইট|ফ্ল্যাশলাইট|টর্চ|torch|flashlight)$/iu.test(text) || /^turn\s+off\s+(?:the\s+)?flashlight$/i.test(text)) return { action: 'set_flashlight', enabled: false };
  let contactCall = /^(?:এই নাম্বারে থাকা )?(.+?)\s*(?:কে|কে)?\s*(?:কল|ফোন)\s*(?:করো|কর|দাও)$/iu.exec(text) || /^(?:কল|ফোন)\s*(?:করো|কর|দাও)\s+(.+?)(?:\s*কে)?$/iu.exec(text) || /^call\s+(.+)$/i.exec(text);
  if (contactCall && contactCall[1].trim() && !/^[+0-9][0-9 ()+-]*$/.test(contactCall[1].trim())) return { action: 'call_contact', recipient: contactCall[1].trim().replace(/\s+নামে$/, '') };
  call = /^(?:কল\s*(?:করো|কর|দাও)|ফোন\s*করো|call|dial)$/iu.exec(text);
  if (call) return null;
  const alarm = /^(?:set\s+)?(?:an?\s+)?(?:alarm|অ্যালার্ম|আলার্ম|এলার্ম)\s*(?:for|at|টি)?\s*(\d{1,2})(?::(\d{2}))?\s*(am|pm|এএম|পিএম)?$/iu.exec(text)
    || /^(?:wake me(?: up)? at)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?$/i.exec(text)
    || /^(\d{1,2})(?::(\d{2}))?\s*টা(?:য়|র)?\s*(?:alarm|অ্যালার্ম|আলার্ম)\s*(?:দাও|দে|set|করো)?$/iu.exec(text);
  if (alarm) {
    let hour = Number(alarm[1]); const minute = alarm[2] ? Number(alarm[2]) : 0; const suffix = (alarm[3] || '').toLowerCase();
    if (suffix === 'pm' || suffix === 'পিএম') { if (hour < 12) hour += 12; }
    if (suffix === 'am' || suffix === 'এএম') { if (hour === 12) hour = 0; }
    if (hour <= 23 && minute <= 59) return { action: 'set_alarm', hour, minute };
  }
  const timer = /^(?:set\s+)?(?:a\s+)?timer\s+(?:for\s+)?(\d{1,4})\s*(s|sec|secs|second|seconds|m|min|mins|minute|minutes|h|hr|hour|hours|সেকেন্ড|মিনিট|ঘণ্টা)$/iu.exec(text)
    || /^(\d{1,4})\s*(s|sec|secs|second|seconds|m|min|mins|minute|minutes|h|hr|hour|hours|সেকেন্ড|মিনিট|ঘণ্টা)\s*(?:ের|এর)?\s*(?:টাইমার|timer)$/iu.exec(text);
  if (timer) {
    const amount = Number(timer[1]); const unit = timer[2].toLowerCase();
    const factor = /^(h|hr|hour|hours|ঘণ্টা)$/.test(unit) ? 3600 : /^(m|min|mins|minute|minutes|মিনিট)$/.test(unit) ? 60 : 1;
    const seconds = amount * factor;
    if (seconds >= 1 && seconds <= 86400) return { action: 'set_timer', seconds };
  }
  const volume = /^(?:set\s+)?(?:media\s+)?volume\s+(?:to\s+)?(\d{1,3})\s*%?$/i.exec(text);
  if (volume) { const level = Number(volume[1]); if (level <= 100) return { action: 'set_volume', level }; }
  if (/^(?:volume|sound)\s+(up|down|বাড়াও|কমাও)$/i.test(text)) return { action: 'set_volume', direction: /down|কমাও/i.test(text) ? 'down' : 'up' };
  if (/^(?:play|pause|next|previous)\s+(?:song|track|music)$/i.test(text)) return { action: 'media_control', command: /next/i.test(text) ? 'next' : /previous/i.test(text) ? 'previous' : 'play_pause' };
  if (/^(?:device|phone|battery)\s+(?:status|info)$/i.test(text) || /^(?:battery|ফোনের)\s*(?:কত|status|info)$/iu.test(text)) return { action: 'device_status' };
  if (/^(?:open|launch)\s+(?:the\s+)?camera$/i.test(text) || /^ক্যামেরা\s*(?:খোলো|চালু করো)$/u.test(text)) return { action: 'open_camera' };
  const link = /^(?:open|visit|go to)\s+(https?:\/\/\S{3,})$/i.exec(text);
  if (link) return { action: 'open_url', url: link[1] };
  let match = /^(?:open\s+)?(?:youtube|you tube)(?:\s+and)?\s+search(?:\s+for)?\s+(.+)$/i.exec(text) || /^search(?:\s+for)?\s+(.+?)\s+on\s+youtube$/i.exec(text);
  if (match) return { action: 'search_youtube', query: match[1].trim() };
  match = /^(?:open\s+)?(?:chrome|google chrome|browser)(?:\s+and)?\s+search(?:\s+for)?\s+(.+)$/i.exec(text) || /^search(?:\s+for)?\s+(.+?)\s+(?:on|in)\s+(?:chrome|google|browser)$/i.exec(text);
  if (match) return { action: 'search_web', query: match[1].trim() };
  match = /^(?:message|msg|text)\s+(.+?)\s+(?:on|using)\s+(?:whatsapp|wp|wa)\s*:\s*(.+)$/is.exec(text) || /^(?:whatsapp|wp|wa)\s+(?:message|msg)(?:\s+to)?\s+(.+?)\s*:\s*(.+)$/is.exec(text);
  if (match) return { action: 'whatsapp_message', recipient: match[1].trim(), message: match[2].trim() };
  const openText = text.replace(/[.!?।]+$/u, '');
  match = /^(?:open|launch)\s+([\p{L}\p{N}][\p{L}\p{N} ._-]{0,79})$/iu.exec(openText) || /^(whatsapp|wp|wa|youtube|chrome)\s+(?:kholo|khol do|open karo)$/i.exec(openText);
  if (match && !/\band\b/i.test(match[1])) return { action: 'open_app', app: match[1].trim() };
  return null;
}

/** Human-readable label for a tool call, used by the activity strip. */
export function describeAction(action: string, args: Record<string, unknown>): string {
  const first = (key: string) => String(args[key] ?? '').trim();
  switch (action) {
    case 'open_app': return `Opening ${first('app')}`;
    case 'open_url': return 'Opening link';
    case 'search_youtube': return `Searching YouTube for “${first('query')}”`;
    case 'search_web': return `Searching the web for “${first('query')}”`;
    case 'whatsapp_message': return `Drafting WhatsApp to ${first('recipient')}`;
    case 'send_sms': return `Drafting SMS to ${first('recipient')}`;
    case 'call_number': return `Opening dialer for ${first('phone')}`;
    case 'call_contact': return `Calling ${first('recipient')}`;
    case 'set_flashlight': return args.enabled ? 'Turning the flashlight on' : 'Turning the flashlight off';
    case 'open_wifi_settings': return 'Opening Internet settings';
    case 'open_settings_panel': return `Opening ${first('panel')} settings`;
    case 'set_alarm': return `Setting an alarm for ${String(args.hour).padStart(2, '0')}:${String(args.minute).padStart(2, '0')}`;
    case 'set_timer': return `Setting a timer for ${first('seconds')} seconds`;
    case 'create_event': return `Opening calendar for “${first('title')}”`;
    case 'navigate_to': return `Opening Maps for ${first('destination')}`;
    case 'share_text': return 'Opening the share sheet';
    case 'copy_text': return 'Copying to the clipboard';
    case 'media_control': return `Sending media ${first('command')}`;
    case 'set_volume': return args.level !== undefined ? `Setting volume to ${first('level')}%` : `Turning volume ${first('direction')}`;
    case 'device_status': return 'Checking device status';
    case 'list_apps': return 'Reading installed apps';
    case 'open_camera': return 'Opening the camera';
    default: return 'Working on your phone';
  }
}
