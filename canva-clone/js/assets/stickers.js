// Sticker (emoji) library grouped by theme. Extended by generated data.

export const STICKER_GROUPS = [
  { name: 'Celebration', emoji: ['🎉', '🎊', '🎈', '🎂', '🎁', '🥳', '🍾', '✨', '🎆', '🏆', '🎖️', '💝'] },
  { name: 'Symbols', emoji: ['⭐', '🌟', '💫', '❤️', '🧡', '💛', '💚', '💙', '💜', '🖤', '💥', '🔥'] },
];

export function registerStickerGroups(groups) {
  if (!Array.isArray(groups)) return;
  for (const g of groups) {
    const existing = STICKER_GROUPS.find(x => x.name === g.name);
    if (existing) {
      for (const e of g.emoji) if (!existing.emoji.includes(e)) existing.emoji.push(e);
    } else {
      STICKER_GROUPS.push(g);
    }
  }
}
