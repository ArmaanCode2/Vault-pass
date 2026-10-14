// Sample vault shared by the two app demos on the website.
// Sample data only: no real accounts. Each demo deep-copies its own list,
// so editing one device never touches the other until "Sync now" runs.
//
// Both devices start with the same seven entries, plus one entry that only
// that device has (Steam was added on the PC, Dropbox on the phone). A sync
// in the demo merges the two lists, so both end with nine.
(function () {
  'use strict';

  var shared = [
    {
      uid: 'google',
      title: 'Google',
      username: 'kay.dev@gmail.com',
      password: 'kP9#vL2$uWxR7*Qz',
      website: 'accounts.google.com',
      category: 'Personal',
      notes: 'Main account. Backup codes are in the custom field.',
      favorite: true,
      customFields: [{ label: 'Backup code', value: '4821 9930', hidden: true }],
      createdAt: '2026-03-02T09:14:00Z',
      updatedAt: '2026-08-21T18:03:00Z',
      deletedAt: null
    },
    {
      uid: 'github',
      title: 'GitHub',
      username: 'kay-codes',
      password: 'T4p!z9vL2-uW7Qz1rX',
      website: 'github.com',
      category: 'Work',
      notes: 'Security key registered as second factor.',
      favorite: true,
      customFields: [],
      createdAt: '2026-03-02T09:20:00Z',
      updatedAt: '2026-07-11T10:42:00Z',
      deletedAt: null
    },
    {
      uid: 'proton',
      title: 'Proton Mail',
      username: 'kay.secure@proton.me',
      password: 'pM8$2Lp@8vW!9xK#',
      website: 'mail.proton.me',
      category: 'Personal',
      notes: '',
      favorite: true,
      customFields: [],
      createdAt: '2026-03-05T16:01:00Z',
      updatedAt: '2026-06-30T08:15:00Z',
      deletedAt: null
    },
    {
      uid: 'netflix',
      title: 'Netflix',
      username: 'kay.family@gmail.com',
      password: 'Summer2023!',
      website: 'netflix.com',
      category: 'Entertainment',
      notes: 'Family profile.',
      favorite: false,
      customFields: [],
      createdAt: '2026-04-12T20:30:00Z',
      updatedAt: '2026-04-12T20:30:00Z',
      deletedAt: null
    },
    {
      uid: 'spotify',
      title: 'Spotify',
      username: 'kay_music',
      password: 'Summer2023!',
      website: 'spotify.com',
      category: 'Entertainment',
      notes: '',
      favorite: false,
      customFields: [],
      createdAt: '2026-04-12T20:41:00Z',
      updatedAt: '2026-04-12T20:41:00Z',
      deletedAt: null
    },
    {
      uid: 'amazon',
      title: 'Amazon',
      username: 'kay.shopping@gmail.com',
      password: 'Amz#2024$SecureP@ss',
      website: 'amazon.com',
      category: 'Shopping',
      notes: '',
      favorite: false,
      customFields: [],
      createdAt: '2026-05-01T12:00:00Z',
      updatedAt: '2026-09-14T17:26:00Z',
      deletedAt: null
    },
    {
      uid: 'bank',
      title: 'Online banking',
      username: 'kay.r.7741',
      password: 'b4nk',
      website: '',
      category: 'Finance',
      notes: 'Old PIN-style password. Change it.',
      favorite: false,
      customFields: [],
      createdAt: '2026-05-20T07:45:00Z',
      updatedAt: '2026-05-20T07:45:00Z',
      deletedAt: null
    }
  ];

  var onlyOnDesktop = [
    {
      uid: 'steam',
      title: 'Steam',
      username: 'kay_plays',
      password: '7rQ!vN3#xL9@pZ2m',
      website: 'store.steampowered.com',
      category: 'Entertainment',
      notes: 'Added on the PC.',
      favorite: false,
      customFields: [],
      createdAt: '2026-10-09T19:12:00Z',
      updatedAt: '2026-10-09T19:12:00Z',
      deletedAt: null
    }
  ];

  var onlyOnAndroid = [
    {
      uid: 'dropbox',
      title: 'Dropbox',
      username: 'kay.dev@gmail.com',
      password: 'dB5$hK8!qW2#nR7v',
      website: 'dropbox.com',
      category: 'Work',
      notes: 'Added on the phone.',
      favorite: false,
      customFields: [],
      createdAt: '2026-10-10T07:40:00Z',
      updatedAt: '2026-10-10T07:40:00Z',
      deletedAt: null
    }
  ];

  function copy(list) {
    return JSON.parse(JSON.stringify(list));
  }

  window.VaultPassDemoData = {
    owner: 'Kay',
    devices: {
      desktop: { name: "Kay's PC" },
      android: { name: "Kay's phone" }
    },
    // Fresh, independent copies on every call
    vaultFor: function (device) {
      return copy(shared.concat(device === 'desktop' ? onlyOnDesktop : onlyOnAndroid));
    }
  };
})();
