"use strict";

const video = document.querySelector("#tutorial-video");
const chapterLinks = Array.from(document.querySelectorAll("#chapter-list a"));
const changeLocation = (start) => {
  video.currentTime = start;
  video.play().catch(() => video.focus());
};

for (const link of chapterLinks) {
  link.addEventListener("click", (event) => {
    if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    changeLocation(Number(link.dataset.start));
  });
}

video.addEventListener("timeupdate", () => {
  const currentLink = chapterLinks.findLast((link) => video.currentTime >= Number(link.dataset.start));
  for (const link of chapterLinks) {
    if (link === currentLink) link.setAttribute("aria-current", "true");
    else link.removeAttribute("aria-current");
  }
});
