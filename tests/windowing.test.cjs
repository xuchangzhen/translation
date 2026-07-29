const test = require("node:test");
const assert = require("node:assert/strict");
const {
  popupBoundsNearPoint,
  popupWindowPresentation
} = require("../src/lib/windowing.cjs");

test("popup moves above a Windows cursor near the taskbar instead of staying at the bottom", () => {
  const bounds = popupBoundsNearPoint(
    { x: 1500, y: 1010 },
    { x: 0, y: 0, width: 1920, height: 1040 },
    480,
    680
  );
  assert.equal(bounds.y, 312);
  assert.ok(bounds.y + 680 <= 1030);
});

test("popup stays below the cursor when enough work area is available", () => {
  const bounds = popupBoundsNearPoint(
    { x: 200, y: 100 },
    { x: 0, y: 0, width: 1920, height: 1040 },
    480,
    520
  );
  assert.equal(bounds.y, 118);
});

test("unpinned macOS popup starts above the active app and releases on outside click", () => {
  assert.deepEqual(popupWindowPresentation("darwin", false), {
    type: "panel",
    initiallyAboveOtherApps: true,
    brieflyAboveOtherApps: false,
    releaseOnOutsideClick: true
  });
});

test("pinned macOS popup stays floating and Windows keeps normal window behavior", () => {
  assert.deepEqual(popupWindowPresentation("darwin", true), {
    type: "panel",
    initiallyAboveOtherApps: true,
    brieflyAboveOtherApps: false,
    releaseOnOutsideClick: false
  });
  assert.deepEqual(popupWindowPresentation("win32", false), {
    type: undefined,
    initiallyAboveOtherApps: false,
    brieflyAboveOtherApps: true,
    releaseOnOutsideClick: false
  });
  assert.deepEqual(popupWindowPresentation("win32", true), {
    type: undefined,
    initiallyAboveOtherApps: true,
    brieflyAboveOtherApps: false,
    releaseOnOutsideClick: false
  });
});
