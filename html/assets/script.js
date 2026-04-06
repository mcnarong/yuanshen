const revealObserver = new IntersectionObserver((entries) => {
  entries.forEach((entry) => {
    if (!entry.isIntersecting) {
      return;
    }
    entry.target.classList.add("is-visible");
    revealObserver.unobserve(entry.target);
  });
}, { threshold: 0.14 });

document.querySelectorAll(".reveal").forEach((node) => {
  revealObserver.observe(node);
});

document.addEventListener("click", async (event) => {
  const button = event.target.closest("[data-copy]");
  if (!button) {
    return;
  }

  const text = button.getAttribute("data-copy") || "";
  const original = button.textContent;

  try {
    await navigator.clipboard.writeText(text);
    button.textContent = "已复制";
    button.classList.add("is-done");
    setTimeout(() => {
      button.textContent = original;
      button.classList.remove("is-done");
    }, 1400);
  } catch (error) {
    button.textContent = "复制失败";
    setTimeout(() => {
      button.textContent = original;
    }, 1400);
  }
});
