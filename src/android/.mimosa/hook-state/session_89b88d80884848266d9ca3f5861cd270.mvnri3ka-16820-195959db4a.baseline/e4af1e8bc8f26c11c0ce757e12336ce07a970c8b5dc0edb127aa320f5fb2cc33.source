"""Mirror of the Kotlin SupervisorSessionRunner loop for local verification."""

import asyncio
import re


class Outcome:
    def __init__(self, kind, summary=None, reason=None):
        self.kind = kind
        self.summary = summary
        self.reason = reason


class Inbox:
    def __init__(self):
        self.pending = []

    def push(self, message):
        if message and message.strip():
            self.pending.append(message.strip())

    def drain(self):
        out = list(self.pending)
        self.pending.clear()
        return out


class FakeSeat:
    def __init__(self, replies):
        self.calls = []
        self.queue = list(replies)
        self.fail_with = None

    def speak(self, user, system):
        self.calls.append(user)
        if self.fail_with:
            raise self.fail_with
        return self.queue.pop(0) if self.queue else ""


def envelope(task, acceptance):
    return f"## Task\n- {task}\n\n## Expected result\n- {acceptance}"


class TurnMachine:
    def __init__(self, max_worker_turns):
        self.phase = "INSTRUCTING"
        self.max_worker_turns = max_worker_turns
        self.worker_turns = 0
        self.strikes = 0
        self.force_closed = False

    def on_supervisor_message(self, text, acceptance):
        if self.phase in ("DONE", "FAILED", "USER_HALTED"):
            return self.phase
        verdict = parse_done(text, acceptance)
        if verdict is not None:
            if not is_rubber_stamp(verdict, acceptance):
                self.phase = "DONE"
                return self.phase
            self.strikes += 1
            self.phase = "FAILED" if self.strikes >= 2 else "REVIEWING"
            return self.phase
        if self.worker_turns >= self.max_worker_turns:
            self.force_closed = True
            self.phase = "FAILED"
            return self.phase
        self.worker_turns += 1
        self.phase = "EXECUTING"
        return self.phase

    def on_worker_report(self):
        if self.phase == "EXECUTING":
            self.phase = "REVIEWING"
        return self.phase


def parse_done(text, acceptance):
    for line in text.splitlines():
        m = re.match(r"^DONE:\s*(.+)$", line.strip(), re.IGNORECASE)
        if m:
            summary = m.group(1).strip()
            if summary:
                return {"summary": summary, "refs": referenced(summary, acceptance)}
    return None


def referenced(summary, acceptance):
    low = summary.lower()
    return [a for a in acceptance if a.lower() in low and len(a) >= 4]


def is_rubber_stamp(verdict, acceptance):
    return not verdict["refs"]


def validate_instruction(raw):
    if "## Task" not in raw or "## Expected result" not in raw:
        return ("Rejected", "missing section")
    return ("Valid", None)


def acceptance_from(text):
    # bullets under ## Expected result
    in_section = False
    out = []
    for line in text.splitlines():
        if line.strip().startswith("## "):
            in_section = "Expected result" in line
            continue
        if in_section and line.strip().startswith("- "):
            out.append(line.strip()[2:].strip())
    return out


def first_turn(objective, context_digest, inbox, recovery):
    out = f"目标：{objective}"
    if context_digest:
        out += f"\n\n会话上下文：\n{context_digest}"
    if recovery:
        out += f"\n\n{recovery}"
    if inbox:
        out += "\n\n用户插话（按顺序）：\n" + "\n".join(f"- {m}" for m in inbox)
    out += "\n\n请发出第一条 worker 指令。"
    return out


def review_turn(objective, last_report, acceptance, inbox, turns, max_turns, bounce=None):
    out = f"目标：{objective}\n\nworker 上一轮报告：\n{last_report}"
    out += "\n\n上一条指令的验收标准：\n" + "\n".join(f"- {a}" for a in acceptance)
    if inbox:
        out += "\n\n用户插话（按顺序）：\n" + "\n".join(f"- {m}" for m in inbox)
    if bounce:
        out += f"\n\n{bounce}"
    out += f"\n\nworker 回合预算：已用 {turns} / {max_turns}。"
    return out


def cap_reached(objective, turns):
    return f"目标：{objective}\n\nworker 回合预算已耗尽（{turns} 轮）。"


def bounce_envelope(reason):
    return f"你上一条指令被信封校验拒绝：{reason}"


def bounce_rubber(acceptance):
    return "你的 DONE 没有引用任何验收标准的原词，被判定为橡皮图章。"


MAX_SUPERVISOR_CALLS = 400


def _drive(objective, machine, acceptance, last_report, calls, speak_sup,
           speak_worker, inbox, max_worker_turns, on_round, terminal, prompt):
    """Shared loop body; speak_sup/speak_worker are plain callables."""
    while True:
        calls += 1
        if calls > MAX_SUPERVISOR_CALLS:
            return terminal("failed", "supervisor call budget exceeded")
        text = speak_sup(prompt)
        verdict = parse_done(text, acceptance)
        if verdict is None:
            kind, reason = validate_instruction(text)
            if kind == "Rejected":
                prompt = review_turn(
                    objective, last_report, acceptance, inbox.drain(),
                    machine.worker_turns, max_worker_turns,
                    bounce_envelope(reason),
                )
                continue
            acceptance = acceptance_from(text)
            phase = machine.on_supervisor_message(text, acceptance)
            if phase == "EXECUTING":
                worker_prompt = f"目标：{objective}\n\n{text}"
                report = speak_worker(worker_prompt)
                machine.on_worker_report()
                last_report = report
                if on_round:
                    on_round(machine.worker_turns, text, report)
                if machine.worker_turns >= max_worker_turns:
                    prompt = cap_reached(objective, machine.worker_turns)
                    continue
            prompt = review_turn(
                objective, last_report, acceptance, inbox.drain(),
                machine.worker_turns, max_worker_turns, None,
            )
            continue
        phase = machine.on_supervisor_message(text, acceptance)
        if phase == "DONE":
            return terminal("done", verdict["summary"])
        if phase == "FAILED":
            reason = (
                f"worker turn budget exhausted ({machine.worker_turns} turns)"
                if machine.force_closed
                else "second rubber-stamped DONE without acceptance evidence"
            )
            return terminal("failed", reason)
        prompt = review_turn(
            objective, last_report, acceptance, inbox.drain(),
            machine.worker_turns, max_worker_turns, bounce_rubber(acceptance),
        )


class Runner:
    @staticmethod
    def run(objective, context_digest, max_worker_turns, sup, worker, inbox,
            recovery=None, on_round=None, on_terminal=None):
        machine = TurnMachine(max_worker_turns)
        acceptance = []
        last_report = ""
        calls = 0

        def terminal(state, summary):
            if on_terminal:
                on_terminal(state, summary)
            if state == "done":
                return Outcome("DONE", summary=summary or "")
            return Outcome("FAILED", reason=summary or state)

        prompt = first_turn(objective, context_digest, inbox.drain(), recovery)
        try:
            return _drive(
                objective, machine, acceptance, last_report, calls,
                lambda p: sup(p, "sys"), lambda p: worker(p, "sys"),
                inbox, max_worker_turns, on_round, terminal, prompt,
            )
        except asyncio.CancelledError:
            if on_terminal:
                on_terminal("user_halted", None)
            raise
        except Exception as e:
            return terminal("failed", str(e))

    @staticmethod
    async def run_async(objective, context_digest, max_worker_turns, sup, worker,
                        inbox, recovery=None, on_round=None, on_terminal=None):
        machine = TurnMachine(max_worker_turns)
        acceptance = []
        last_report = ""
        calls = 0

        def terminal(state, summary):
            if on_terminal:
                on_terminal(state, summary)
            if state == "done":
                return Outcome("DONE", summary=summary or "")
            return Outcome("FAILED", reason=summary or state)

        prompt = first_turn(objective, context_digest, inbox.drain(), recovery)

        async def sup_speak(p):
            return await sup(p, "sys")

        async def worker_speak(p):
            return await worker(p, "sys")

        # _drive is sync; drive it stepwise via an async trampoline: simplest
        # correct approach — run the sync loop but await seats through a shim
        # is impossible, so inline the async loop here (duplicated on purpose,
        # mirrors Kotlin's single loop with suspend calls).
        try:
            while True:
                calls += 1
                if calls > MAX_SUPERVISOR_CALLS:
                    return terminal("failed", "supervisor call budget exceeded")
                text = await sup(prompt, "sys")
                verdict = parse_done(text, acceptance)
                if verdict is None:
                    kind, reason = validate_instruction(text)
                    if kind == "Rejected":
                        prompt = review_turn(
                            objective, last_report, acceptance, inbox.drain(),
                            machine.worker_turns, max_worker_turns,
                            bounce_envelope(reason),
                        )
                        continue
                    acceptance = acceptance_from(text)
                    phase = machine.on_supervisor_message(text, acceptance)
                    if phase == "EXECUTING":
                        worker_prompt = f"目标：{objective}\n\n{text}"
                        report = await worker(worker_prompt, "sys")
                        machine.on_worker_report()
                        last_report = report
                        if on_round:
                            on_round(machine.worker_turns, text, report)
                        if machine.worker_turns >= max_worker_turns:
                            prompt = cap_reached(objective, machine.worker_turns)
                            continue
                    prompt = review_turn(
                        objective, last_report, acceptance, inbox.drain(),
                        machine.worker_turns, max_worker_turns, None,
                    )
                    continue
                phase = machine.on_supervisor_message(text, acceptance)
                if phase == "DONE":
                    return terminal("done", verdict["summary"])
                if phase == "FAILED":
                    reason = (
                        f"worker turn budget exhausted ({machine.worker_turns} turns)"
                        if machine.force_closed
                        else "second rubber-stamped DONE without acceptance evidence"
                    )
                    return terminal("failed", reason)
                prompt = review_turn(
                    objective, last_report, acceptance, inbox.drain(),
                    machine.worker_turns, max_worker_turns, bounce_rubber(acceptance),
                )
        except asyncio.CancelledError:
            if on_terminal:
                on_terminal("user_halted", None)
            raise
        except Exception as e:
            return terminal("failed", str(e))
