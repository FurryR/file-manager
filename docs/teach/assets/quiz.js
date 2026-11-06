/* quiz.js — 共享练习组件。
   用法：
   1) 选择题：
      <div class="quiz" data-quiz>
        <p class="quiz-q">题干</p>
        <button class="opt" data-why="错误原因">选项</button>
        <button class="opt" data-ok data-why="正确原因">选项</button>
        <p class="quiz-explain" hidden></p>
      </div>
   2) 填空核验：
      <span class="trace" data-expect="/alpine/home">
        <input class="answer" aria-label="答案">
        <button class="check">核对</button>
        <span class="verdict" aria-live="polite"></span>
      </span>
   所有选项在 HTML 里静态写死，正确项的位置逐题变化。 */

(function () {
  "use strict";

  function normalise(value) {
    return value
      .trim()
      .replace(/\s+/g, " ")
      .replace(/^["'`]|["'`]$/g, "");
  }

  function initQuiz(root) {
    var options = Array.prototype.slice.call(root.querySelectorAll(".opt"));
    var explain = root.querySelector(".quiz-explain");
    var settled = false;

    function disableAll() {
      options.forEach(function (button) {
        button.disabled = true;
      });
    }

    options.forEach(function (button) {
      button.addEventListener("click", function () {
        if (settled) return;
        var correct = button.hasAttribute("data-ok");
        button.classList.add(correct ? "correct" : "wrong");
        button.setAttribute("aria-pressed", correct ? "true" : "false");

        if (correct) {
          settled = true;
          disableAll();
          if (explain) {
            explain.hidden = false;
            explain.textContent = button.getAttribute("data-why") || "正确。";
          }
          root.classList.add("solved");
        } else {
          if (explain) {
            explain.hidden = false;
            explain.textContent = button.getAttribute("data-why") || "再看一遍题干。";
            explain.classList.add("hint");
          }
        }
      });
    });

    root.addEventListener("keydown", function (event) {
      if (event.key === "Enter" && !settled) {
        var focused = document.activeElement;
        if (focused && focused.classList.contains("opt")) focused.click();
      }
    });
  }

  function initTrace(host) {
    var input = host.querySelector(".answer");
    var button = host.querySelector(".check");
    var verdict = host.querySelector(".verdict");
    if (!input || !button || !verdict) return;

    var expected = normalise(host.getAttribute("data-expect") || "");
    var attempts = 0;

    function mark(state, message) {
      host.classList.remove("ok", "bad");
      host.classList.add(state);
      verdict.textContent = message;
    }

    function check() {
      var answer = normalise(input.value);
      if (!answer) {
        mark("bad", "先写一个答案。");
        return;
      }
      attempts += 1;
      if (answer === expected) {
        mark("ok", "对。");
        input.readOnly = true;
        button.disabled = true;
        return;
      }
      var hint =
        answer.replace(/\/+$/, "") === expected.replace(/\/+$/, "")
          ? "只差结尾斜杠。"
          : attempts >= 2
            ? "再看一次 scheme 与 authority 的分界。"
            : "不对，拆成 scheme / authority / segments 三段再看。";
      mark("bad", hint);
    }

    button.addEventListener("click", check);
    input.addEventListener("keydown", function (event) {
      if (event.key === "Enter") {
        event.preventDefault();
        check();
      }
    });
  }

  function init() {
    Array.prototype.forEach.call(
      document.querySelectorAll("[data-quiz]"),
      initQuiz
    );
    Array.prototype.forEach.call(
      document.querySelectorAll(".trace[data-expect]"),
      initTrace
    );
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
