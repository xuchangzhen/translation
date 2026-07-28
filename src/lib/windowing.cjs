function popupBoundsNearPoint(cursor, area, width, height, margin = 10) {
  const preferredX = cursor.x + 18;
  const preferredY =
    cursor.y + height + 28 <= area.y + area.height
      ? cursor.y + 18
      : cursor.y - height - 18;
  return {
    x: Math.max(
      area.x + margin,
      Math.min(preferredX, area.x + area.width - width - margin)
    ),
    y: Math.max(
      area.y + margin,
      Math.min(preferredY, area.y + area.height - height - margin)
    )
  };
}

function popupWindowPresentation(platform, pinned) {
  const isMac = platform === "darwin";
  return {
    type: isMac ? "panel" : undefined,
    initiallyAboveOtherApps: isMac || Boolean(pinned),
    releaseOnOutsideClick: isMac && !pinned
  };
}

module.exports = {
  popupBoundsNearPoint,
  popupWindowPresentation
};
