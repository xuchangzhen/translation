import "./styles.css";

const selection = document.querySelector<HTMLDivElement>("#selection")!;
const selectionSize =
  document.querySelector<HTMLSpanElement>("#selection-size")!;
const shadeTop = document.querySelector<HTMLDivElement>("#shade-top")!;
const shadeLeft = document.querySelector<HTMLDivElement>("#shade-left")!;
const shadeRight = document.querySelector<HTMLDivElement>("#shade-right")!;
const shadeBottom = document.querySelector<HTMLDivElement>("#shade-bottom")!;
const selectionActions =
  document.querySelector<HTMLDivElement>("#selection-actions")!;
const reselectButton =
  document.querySelector<HTMLButtonElement>("#selection-reselect")!;
const confirmButton =
  document.querySelector<HTMLButtonElement>("#selection-confirm")!;

let startX = 0;
let startY = 0;
let dragging = false;
let resizingHandle = "";
let movingSelection = false;
let resizeOrigin:
  | {
      pointerX: number;
      pointerY: number;
      rect: { x: number; y: number; width: number; height: number };
    }
  | undefined;
let moveOrigin:
  | {
      pointerX: number;
      pointerY: number;
      rect: { x: number; y: number; width: number; height: number };
    }
  | undefined;
let pendingRect: { x: number; y: number; width: number; height: number } | null =
  null;

function setSelection(x: number, y: number, width: number, height: number) {
  const viewportWidth = window.innerWidth;
  const viewportHeight = window.innerHeight;
  selection.style.left = `${x}px`;
  selection.style.top = `${y}px`;
  selection.style.width = `${width}px`;
  selection.style.height = `${height}px`;
  selection.style.display = width > 1 && height > 1 ? "block" : "none";
  selectionSize.textContent = `${Math.round(width)} × ${Math.round(height)}`;
  shadeTop.style.cssText = `left:0;top:0;width:${viewportWidth}px;height:${y}px`;
  shadeLeft.style.cssText = `left:0;top:${y}px;width:${x}px;height:${height}px`;
  shadeRight.style.cssText =
    `left:${x + width}px;top:${y}px;width:${Math.max(0, viewportWidth - x - width)}px;height:${height}px`;
  shadeBottom.style.cssText =
    `left:0;top:${y + height}px;width:${viewportWidth}px;height:${Math.max(0, viewportHeight - y - height)}px`;
}

function positionActions(
  x: number,
  y: number,
  width: number,
  height: number
) {
  selectionActions.hidden = false;
  const actionsWidth = selectionActions.offsetWidth || 178;
  const actionsHeight = selectionActions.offsetHeight || 44;
  const left = Math.min(
    window.innerWidth - actionsWidth - 14,
    Math.max(14, x + width - actionsWidth)
  );
  const below = y + height + 40;
  const top =
    below + actionsHeight <= window.innerHeight - 14
      ? below
      : Math.max(14, y - actionsHeight - 14);
  selectionActions.style.left = `${left}px`;
  selectionActions.style.top = `${top}px`;
}

function resetShade() {
  document.body.classList.remove("selecting");
  document.body.classList.remove("confirming");
  document.body.classList.remove("resizing");
  document.body.classList.remove("moving");
  resizingHandle = "";
  resizeOrigin = undefined;
  movingSelection = false;
  moveOrigin = undefined;
  pendingRect = null;
  selectionActions.hidden = true;
  confirmButton.disabled = false;
  confirmButton.textContent = "截图翻译";
  selection.style.display = "none";
  shadeTop.style.cssText = "inset:0";
  shadeLeft.style.cssText = "display:none";
  shadeRight.style.cssText = "display:none";
  shadeBottom.style.cssText = "display:none";
}

window.addEventListener("mousedown", (event) => {
  if (event.button !== 0) return;
  const resizeTarget = (event.target as HTMLElement).closest<HTMLElement>(
    "[data-resize-handle]"
  );
  if (resizeTarget && pendingRect) {
    event.preventDefault();
    event.stopPropagation();
    resizingHandle = resizeTarget.dataset.resizeHandle || "";
    resizeOrigin = {
      pointerX: event.clientX,
      pointerY: event.clientY,
      rect: { ...pendingRect }
    };
    selectionActions.hidden = true;
    document.body.classList.add("resizing");
    return;
  }
  if ((event.target as HTMLElement).closest("#selection-actions")) return;
  if (
    (event.target as HTMLElement).closest("#selection") &&
    pendingRect
  ) {
    event.preventDefault();
    event.stopPropagation();
    movingSelection = true;
    moveOrigin = {
      pointerX: event.clientX,
      pointerY: event.clientY,
      rect: { ...pendingRect }
    };
    selectionActions.hidden = true;
    document.body.classList.add("moving");
    return;
  }
  document.body.classList.remove("confirming");
  selectionActions.hidden = true;
  pendingRect = null;
  document.body.classList.add("selecting");
  dragging = true;
  startX = event.clientX;
  startY = event.clientY;
  setSelection(startX, startY, 0, 0);
});

window.addEventListener("mousemove", (event) => {
  if (movingSelection && moveOrigin) {
    const x = Math.min(
      window.innerWidth - moveOrigin.rect.width,
      Math.max(
        0,
        moveOrigin.rect.x + event.clientX - moveOrigin.pointerX
      )
    );
    const y = Math.min(
      window.innerHeight - moveOrigin.rect.height,
      Math.max(
        0,
        moveOrigin.rect.y + event.clientY - moveOrigin.pointerY
      )
    );
    pendingRect = {
      x,
      y,
      width: moveOrigin.rect.width,
      height: moveOrigin.rect.height
    };
    setSelection(x, y, pendingRect.width, pendingRect.height);
    return;
  }
  if (resizingHandle && resizeOrigin) {
    const minimumSize = 16;
    const deltaX = event.clientX - resizeOrigin.pointerX;
    const deltaY = event.clientY - resizeOrigin.pointerY;
    let left = resizeOrigin.rect.x;
    let top = resizeOrigin.rect.y;
    let right = resizeOrigin.rect.x + resizeOrigin.rect.width;
    let bottom = resizeOrigin.rect.y + resizeOrigin.rect.height;
    if (resizingHandle.includes("w")) {
      left = Math.min(right - minimumSize, Math.max(0, left + deltaX));
    }
    if (resizingHandle.includes("e")) {
      right = Math.max(
        left + minimumSize,
        Math.min(window.innerWidth, right + deltaX)
      );
    }
    if (resizingHandle.includes("n")) {
      top = Math.min(bottom - minimumSize, Math.max(0, top + deltaY));
    }
    if (resizingHandle.includes("s")) {
      bottom = Math.max(
        top + minimumSize,
        Math.min(window.innerHeight, bottom + deltaY)
      );
    }
    pendingRect = {
      x: left,
      y: top,
      width: right - left,
      height: bottom - top
    };
    setSelection(
      pendingRect.x,
      pendingRect.y,
      pendingRect.width,
      pendingRect.height
    );
    return;
  }
  if (!dragging) return;
  const x = Math.min(startX, event.clientX);
  const y = Math.min(startY, event.clientY);
  const width = Math.abs(event.clientX - startX);
  const height = Math.abs(event.clientY - startY);
  setSelection(x, y, width, height);
});

window.addEventListener("mouseup", (event) => {
  if (event.button !== 0) return;
  if (movingSelection && pendingRect) {
    movingSelection = false;
    moveOrigin = undefined;
    document.body.classList.remove("moving");
    positionActions(
      pendingRect.x,
      pendingRect.y,
      pendingRect.width,
      pendingRect.height
    );
    return;
  }
  if (resizingHandle && pendingRect) {
    resizingHandle = "";
    resizeOrigin = undefined;
    document.body.classList.remove("resizing");
    positionActions(
      pendingRect.x,
      pendingRect.y,
      pendingRect.width,
      pendingRect.height
    );
    return;
  }
  if (!dragging) return;
  dragging = false;
  const x = Math.min(startX, event.clientX);
  const y = Math.min(startY, event.clientY);
  const width = Math.abs(event.clientX - startX);
  const height = Math.abs(event.clientY - startY);
  if (width < 8 || height < 8) {
    resetShade();
    return;
  }
  pendingRect = { x, y, width, height };
  document.body.classList.remove("selecting");
  document.body.classList.add("confirming");
  positionActions(x, y, width, height);
});

reselectButton.addEventListener("click", (event) => {
  event.stopPropagation();
  resetShade();
});

confirmButton.addEventListener("click", async (event) => {
  event.stopPropagation();
  if (!pendingRect) return;
  confirmButton.disabled = true;
  confirmButton.textContent = "识别中…";
  const completed = await window.lingua.completeSelection(pendingRect);
  if (!completed) {
    confirmButton.disabled = false;
    confirmButton.textContent = "截图翻译";
  }
});

window.addEventListener("keydown", (event) => {
  if (event.key === "Escape") void window.lingua.cancelSelection();
});

window.addEventListener("contextmenu", (event) => {
  event.preventDefault();
  void window.lingua.cancelSelection();
});

resetShade();
