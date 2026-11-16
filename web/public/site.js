// Road Ahead's page. Everything here is an extra: with no script the page still reads in full.
document.documentElement.classList.add("js");
const $ = (sel, root = document) => root.querySelector(sel);
const part = (name) => $(`[data-part="${name}"]`);
const calm = matchMedia("(prefers-reduced-motion: reduce)");

// Parts of a section arrive as it comes into view.
const seen = new IntersectionObserver((entries) => {
  for (const e of entries) if (e.isIntersecting) { e.target.classList.add("in"); seen.unobserve(e.target); }
}, { rootMargin: "0px 0px -8% 0px" });
document.querySelectorAll(".reveal").forEach((el) => seen.observe(el));

// The three distances a tap steps through. The pictures are the field's real output for
// Uriarra Road, drawn by the same code that runs on the Karoo.
const DISTANCES = [
  { km: "2", file: "2000", caption: "Free · no phone · no account" },
  { km: "5", file: "5000", caption: "Tap the field and it shows 5 km." },
  { km: "10", file: "10000", caption: "Tap again for 10 km. It stops at the junction it can't call." },
];
for (const d of DISTANCES) { new Image().src = `assets/field-${d.file}.png`; new Image().src = `assets/tall-${d.file}.png`; }
function swap(img, src, alt) {
  img.style.opacity = 0;
  setTimeout(() => { img.src = src; img.alt = alt; img.style.opacity = 1; }, 180);
}

// On the first screen the field steps through its distances on a timer, while the screen is in view.
let heroStep = 0, heroTimer = null;
function showHero(i) {
  const d = DISTANCES[i];
  swap(part("hero-field"), `assets/field-${d.file}.png`, `The Road ahead field: ${d.km} km of Uriarra Road, coloured by grade`);
  const caption = part("caption");
  caption.style.opacity = 0;
  setTimeout(() => { caption.textContent = d.caption; caption.style.opacity = 1; }, 180);
}
new IntersectionObserver((entries) => {
  clearInterval(heroTimer);
  if (entries[0].isIntersecting && !calm.matches) heroTimer = setInterval(() => { heroStep = (heroStep + 1) % DISTANCES.length; showHero(heroStep); }, 3200);
}, { threshold: 0.4 }).observe($(".stage"));

// The field at poster size: tap it and it does what the real one does.
let tapStep = 0;
$(".bigfield").addEventListener("click", () => {
  tapStep = (tapStep + 1) % DISTANCES.length;
  const d = DISTANCES[tapStep];
  swap(part("tap-field"), `assets/tall-${d.file}.png`, `The field at its tall size: ${d.km} km of Uriarra Road`);
  part("tap-heading").textContent = `The next ${d.km} km.`;
});

// Four stages, one slider.
const row = (a, b) => `<div class="row"><span>${a}</span><b>${b}</b></div>`;
const STAGES = [
  ["First, it finds your road.", row("Nearest road that runs your way", "Uriarra Road") + row("A footpath beside it", "ignored") + row("Until you leave it", "it holds on")],
  ["Then it follows it ahead.", row("A piece with the same name", "carry on") + row("The straightest way on, within 30°", "carry on") + row("A T-junction or a fork", "stop there")],
  ["Next, it reads the heights.", row("Elevation grid", "30 m") + row("A sample along the road", "every 25 m") + row("Bridges and tunnels", "ruled straight")],
  ["Last, it draws the road.", `<img src="assets/field-5000.png" width="480" height="150" alt="The Road ahead field: 5 km of Uriarra Road">` + row("Blocks coloured by grade", "the Karoo's own colours") + row("Downhill", "grey")],
];
const slider = $("#stage");
function showStage(i, quick) {
  const clause = part("how-clause"), fragment = part("how-fragment");
  const set = () => {
    clause.textContent = STAGES[i][0];
    fragment.innerHTML = STAGES[i][1];
    clause.style.opacity = fragment.style.opacity = 1;
    document.querySelectorAll(".stage-names li").forEach((li, n) => li.classList.toggle("on", n === i));
  };
  if (quick) return set();
  clause.style.opacity = fragment.style.opacity = 0;
  setTimeout(set, 200);
}
slider.addEventListener("input", () => showStage(Number(slider.value)));
document.querySelectorAll(".stage-names li").forEach((li, n) => li.addEventListener("click", () => { slider.value = n; showStage(n); }));
showStage(0, true);

// Questions: the first is open; hover, focus or a tap picks another.
const answer = $("#answer");
const questions = [...document.querySelectorAll("#qs button")];
function pick(button) {
  for (const q of questions) {
    q.setAttribute("aria-current", q === button);
    q.parentElement.querySelector(".inline-answer")?.remove();
  }
  answer.style.opacity = 0;
  setTimeout(() => { answer.textContent = button.dataset.a; answer.style.opacity = 1; }, 120);
  const inline = document.createElement("p");
  inline.className = "inline-answer";
  inline.textContent = button.dataset.a;
  button.parentElement.append(inline);
}
for (const q of questions) {
  q.addEventListener("click", () => pick(q));
  q.addEventListener("focus", () => pick(q));
  q.addEventListener("mouseenter", () => pick(q));
}
pick(questions[0]);
