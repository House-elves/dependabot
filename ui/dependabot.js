// The Dependabot elf's tab on Elf Desk, and its dashboard card: Dependabot's open PRs in the repos it handles, by
// whose move each one is. The elf gets each one ready by itself at its refresh (rebases, CI retries, a safety check);
// you merge the ready ones, one by one or with Merge all, whose merge queue merges them one at a time. It reads
// board/board.json (the elf's refresh writes it). Merging, approving CI, re-running it, rebasing and closing are its
// actions, which wait for your click in the desk's bar before anything is written to GitHub.
import {
    LitElement, html, css, svg, shared, cardStyles, spinner, ago, count, settingsButton, Ticking, elf,
} from '/elf-kit/1/kit.js';

/** The tab's sections, by whose move it is (the board's flow.waiting). */
const SECTIONS = [
    { id: 'ready', title: 'Ready to merge', empty: 'Nothing is ready yet: the elf is getting them ready.' },
    { id: 'you', title: 'Needs you' },
    { id: 'queue', title: 'Merge queue', hint: 'One at a time per repo and branch, rebasing the next after each merge. It moves on at each refresh.' },
    { id: 'elf', title: 'Being got ready', hint: 'At each refresh, every 10 minutes: it asks Dependabot to rebase, re-runs failed CI, and checks each bump.' },
];

async function board() {
    return elf.readJson('board', 'board.json');
}

/** owner/repo#123 */
function key(pr) {
    return `${pr.repo}#${pr.number}`;
}

/** A check's link, only if it's an http(s) address (whoever posts a status sets it). */
function webUrl(url) {
    try {
        const u = new URL(url);
        return u.protocol === 'https:' || u.protocol === 'http:' ? u.href : null;
    } catch {
        return null;
    }
}

/** The PR icon, coloured by CI, as the GitHub board draws it. */
function prIcon(pr) {
    const cls = { SUCCESS: 'ok', FAILURE: 'bad', ERROR: 'bad', PENDING: 'run', EXPECTED: 'run' }[pr.ci] ?? 'idle';
    return html`<svg class="icon ${cls}" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6"
                    stroke-linecap="round" role="img" aria-label="Pull request">
        ${svg`<circle cx="4" cy="3.5" r="1.8"/><circle cx="4" cy="12.5" r="1.8"/><circle cx="12" cy="12.5" r="1.8"/>
              <path d="M4 5.3v5.4M12 10.7V6.5a2 2 0 0 0-2-2H7.5M9 2.8 7.3 4.5 9 6.2"/>`}
    </svg>`;
}

/** CI as a chip: what the icon's colour can't say on its own. */
function ciChip(pr) {
    switch (pr.ci) {
        case 'SUCCESS': return pr.mergeState === 'CLEAN' || pr.mergeState === 'HAS_HOOKS'
            ? html`<span class="chip ok">CI passed</span>`
            : pr.mergeState === 'BEHIND' ? html`<span class="chip ok">CI passed</span><span class="chip warn">Behind its base</span>`
            : html`<span class="chip run" title="GitHub says it can't merge yet">Can't merge yet</span>`;
        case 'FAILURE': case 'ERROR': return html`<span class="chip bad">CI failing</span>`;
        case 'PENDING': case 'EXPECTED': return html`<span class="chip run">${spinner}CI in progress</span>`;
        case 'AWAITING_APPROVAL': return html`<span class="chip warn">CI needs approval</span>`;
        default: return html`<span class="chip">No CI yet</span>`;
    }
}

/** "bson 5.14.0 → 5.15.0", a major bump marked. */
function bumpChip(row) {
    const b = row.bump;
    if (!b) return html`<span class="chip" title="A grouped update: several dependencies at once">Grouped</span>`;
    const name = b.dependency.includes(':') ? b.dependency.split(':').pop() : b.dependency;
    return html`<span class="bump ${b.kind}" title="${b.dependency}, a ${b.kind} bump">
        ${name} <span class="from">${b.from}</span> → <b>${b.to}</b></span>${b.kind === 'major' ? html` <span class="chip warn">Major</span>` : ''}`;
}

/** The safety check's verdict, its reason on hover. */
function checkChip(row) {
    const c = row.check;
    if (!c) return '';
    return c.verdict === 'safe'
        ? html`<span class="chip ok" title=${c.reason ?? ''}>Checked safe</span>`
        : html`<span class="chip warn" title=${c.reason ?? ''}>Look first</span>`;
}

/** "into 2.x" when it doesn't go into the repo's default branch. */
function baseChip(pr) {
    if (!pr.base || pr.base === pr.defaultBranch) return '';
    return html`<span class="chip" title="It merges into ${pr.base}, not ${pr.defaultBranch ?? 'the default branch'}">into ${pr.base}</span>`;
}

class DependabotTab extends LitElement {

    static properties = {
        _board: { state: true },
        _state: { state: true },
        _loading: { state: true },
        _starting: { state: true },
        _doing: { state: true },
        _error: { state: true },
        _rowError: { state: true },
        _ciOpen: { state: true },
        _ci: { state: true },
        _ciBusy: { state: true },
        _ciError: { state: true },
    };

    static styles = [shared, css`
        .page { display: grid; gap: 26px; }
        h1 { font-size: 34px; line-height: 1.15; font-weight: 600; letter-spacing: -0.022em; }
        .sub { margin: 6px 0 0; color: var(--muted); font-size: 14px; }
        .bar { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 14px; }
        .failed { display: grid; gap: 8px; }
        pre {
            margin: 0; padding: 12px 14px; border-radius: var(--r-control); overflow-x: auto;
            background: var(--log-bg); color: var(--log-ink); font: 12.5px/1.5 var(--font-code); white-space: pre-wrap;
        }
        section { display: grid; gap: 12px; }
        .sec-head { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
        .sec-head h2 { font-size: 18px; font-weight: 600; letter-spacing: -0.01em; }
        .sec-head .n { color: var(--muted); font-size: 15px; }
        .sec-head .hint { color: var(--muted); font-size: 13px; }
        section.ready > .panel, section.you > .panel { box-shadow: inset 3px 0 0 var(--accent); }
        ul { list-style: none; margin: 0; padding: 4px 0; }
        li {
            display: grid; grid-template-columns: 16px minmax(0, 1fr) auto; gap: 4px 14px; align-items: start;
            padding: 12px 18px;
        }
        li + li { border-top: 1px solid var(--line); }
        li:hover { background: color-mix(in srgb, var(--surface-2) 55%, transparent); }
        li .icon { margin-top: 3px; }
        .main { min-width: 0; display: grid; gap: 5px; }
        .title { color: var(--ink); font-weight: 500; text-decoration: none; overflow-wrap: anywhere; }
        .title:hover { text-decoration: underline; }
        .bump { font-size: 13px; color: var(--muted); font-family: var(--font-code); }
        .bump .from { text-decoration: line-through; text-decoration-color: var(--line); }
        .bump b { color: var(--ink); font-weight: 600; }
        .bump.major b { color: var(--warn); }
        .meta { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 12px; font-size: 13px; color: var(--muted); }
        .say { color: var(--ink); }
        .steps { display: inline-flex; align-items: center; gap: 6px; }
        .seg { display: inline-flex; gap: 3px; }
        .seg i { width: 14px; height: 4px; border-radius: 2px; background: var(--line); }
        .seg i.done { background: color-mix(in srgb, var(--accent) 45%, var(--line)); }
        .seg i.now { background: var(--accent); }
        .stage { font-weight: 500; }
        .acts { display: flex; flex-wrap: wrap; gap: 8px; justify-content: flex-end; align-items: center; max-width: 26rem; }
        .row-error { margin: 8px 0 0; font-size: 13.5px; display: flex; flex-wrap: wrap; gap: 6px 10px; align-items: center; }
        .empty { color: var(--muted); padding: 16px 18px; margin: 0; font-size: 14px; }
        button.chip { border: 0; cursor: pointer; font-family: var(--font); }
        .panel-ci {
            display: grid; gap: 10px; padding: 12px 14px; margin-top: 6px; border: 1.5px solid var(--bad);
            border-radius: var(--r-panel); background: var(--surface); font-size: 14px; color: var(--ink);
        }
        .panel-ci ul { padding: 0 0 0 18px; display: grid; gap: 2px; list-style: disc; }
        .panel-ci li { display: list-item; padding: 0; border: 0; overflow-wrap: anywhere; }
        .panel-ci li:hover { background: none; }
        .panel-ci .acts { justify-content: flex-start; max-width: none; }
        .note { color: var(--muted); font-size: 13px; margin: 0; }
        .merged { display: grid; gap: 6px; padding: 14px 18px; font-size: 14px; }
        .merged a { color: var(--ink); }
        .notice { padding: 18px 20px; display: grid; gap: 8px; max-width: 46rem; }
        .notice p { margin: 0; }
        .sk-row { display: grid; grid-template-columns: 16px 1fr 90px; gap: 14px; padding: 15px 18px; }
        .sk-row + .sk-row { border-top: 1px solid var(--line); }
        @media (max-width: 600px) {
            h1 { font-size: 26px; }
            li { grid-template-columns: 16px minmax(0, 1fr); padding: 12px 14px; }
            .acts { grid-column: 2; justify-content: flex-start; }
        }
    `];

    constructor() {
        super();
        new Ticking(this);
        this._loading = true;
        this._doing = null;
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
        this._off = elf.onChange(() => this._load());
    }

    disconnectedCallback() {
        this._off?.();
        super.disconnectedCallback();
    }

    async _load() {
        try {
            this._state = await elf.state();
            this._board = await board();
            this._error = null;
        } catch (e) {
            this._error = `Couldn't load the PRs: ${e.message}`;
        } finally {
            this._loading = false;
        }
    }

    get _refreshing() {
        return this._starting === 'board' || !!this._state?.running?.board;
    }

    async _refresh() {
        this._starting = 'board';
        this._error = null;
        try {
            this._state = await elf.run('board');
            await this._load();
        } catch (e) {
            this._error = e.message;
        } finally {
            this._starting = null;
        }
    }

    get _ready() {
        return (this._board?.rows ?? []).filter(r => r.flow.ready);
    }

    async _mergeAll() {
        const n = this._ready.length;
        this._doing = 'merge-all';
        this._error = null;
        try {
            await elf.act('merge-all', { count: String(n) });
            await this._load();
        } catch (e) {
            if (!e.cancelled) this._error = `Merge all: ${e.message}`;
        } finally {
            this._doing = null;
        }
    }

    /** A row's step: one of the actions, each written to GitHub only once you confirm in the desk's bar. */
    async _step(row, next) {
        const pr = row.pr;
        const values = { repo: pr.repo, pr: pr.number };
        if (next.id === 'merge') values.headSha = pr.headSha;
        this._doing = `${key(pr)} ${next.id}`;
        this._rowError = null;
        try {
            await elf.act(next.id, values);
            await this._load();
        } catch (e) {
            if (!e.cancelled) this._rowError = { key: key(pr), text: `${next.label}: ${e.message}` };
        } finally {
            this._doing = null;
        }
    }

    render() {
        const b = this._board;
        return html`
            <div class="page">
                <div class="page-top"><div>
                    <h1>Dependabot</h1>
                    <p class="sub">${b ? html`${count(b.rows.length, 'open PR')} in ${b.repos.join(', ')}. Updated ${ago(b.fetchedAt)}.`
                        : this._loading ? '' : 'Not read from GitHub yet.'}</p>
                </div>${settingsButton(null, 'Dependabot settings')}</div>
                ${this._bar()}
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
                ${(b?.errors ?? []).map(e => html`<p class="error">GitHub couldn't be read for ${e}. Its PRs are from the read before.</p>`)}
                ${this._loading && !b ? this._skeleton() : b ? this._sections(b) : this._noBoard()}
                ${b?.merged?.length ? this._merged(b) : ''}
            </div>`;
    }

    _bar() {
        const failed = !this._state?.running?.board && this._state?.failed?.board;
        const n = this._ready.length;
        return html`
            <div class="bar">
                <button ?disabled=${this._refreshing} @click=${() => this._refresh()}>
                    ${this._refreshing ? spinner : ''}${this._refreshing ? 'Refreshing' : 'Refresh'}</button>
                ${n ? html`<button class="primary" ?disabled=${this._doing !== null}
                    title="Every bump the safety check said is safe, into the merge queue: it merges them one at a time per repo and branch, rebasing each after the merge before it and waiting for its CI"
                    @click=${() => this._mergeAll()}>${this._doing === 'merge-all' ? spinner : ''}Merge all ${n}</button>` : ''}
            </div>
            ${failed ? html`<div class="failed" role="alert">
                <p class="error">The last refresh failed. The end of its log:</p><pre>${failed}</pre></div>` : ''}`;
    }

    _noBoard() {
        const repos = this._board?.repos ?? [];
        return html`<div class="panel notice">
            <p>${this._refreshing ? 'Reading the open Dependabot PRs from GitHub for the first time.'
                : repos.length ? "There's no board yet: Refresh reads GitHub."
                : 'Add the repos whose Dependabot PRs it handles in Settings, then Refresh.'}</p></div>`;
    }

    _skeleton() {
        const rows = Array.from({ length: 3 }, () => html`<div class="sk-row"><div class="skeleton" style="height:16px"></div>
            <div style="display:grid;gap:8px"><div class="skeleton" style="height:12px;width:70%"></div>
            <div class="skeleton" style="height:12px;width:40%"></div></div><div class="skeleton" style="height:30px"></div></div>`);
        return html`<div class="panel" aria-busy="true" aria-label="Loading the PRs">${rows}</div>`;
    }

    _sections(b) {
        if (!b.rows.length) return html`<div class="panel"><p class="empty">No open Dependabot PRs.</p></div>`;
        const by = Object.fromEntries(SECTIONS.map(s => [s.id, []]));
        b.rows.forEach(r => (by[r.flow.waiting] ?? by.you).push(r));
        // The queue in its order: line by line, as it merges them.
        const order = new Map((b.queue ?? []).map((q, i) => [`${q.repo}#${q.pr}`, i]));
        by.queue.sort((x, y) => (order.get(key(x.pr)) ?? 0) - (order.get(key(y.pr)) ?? 0));
        return SECTIONS.map(s => {
            const rows = by[s.id];
            if (!rows.length && s.id !== 'ready') return '';
            return html`<section class=${s.id} aria-labelledby="h-${s.id}">
                <div class="sec-head"><h2 id="h-${s.id}">${s.title}</h2><span class="n">${rows.length}</span>
                    ${s.hint ? html`<span class="hint">${s.hint}</span>` : ''}</div>
                <div class="panel">${rows.length ? html`<ul>${rows.map(r => this._row(r))}</ul>` : html`<p class="empty">${s.empty}</p>`}</div>
            </section>`;
        });
    }

    _merged(b) {
        return html`<section aria-labelledby="h-merged">
            <div class="sec-head"><h2 id="h-merged">Merged lately</h2><span class="n">${b.merged.length}</span></div>
            <div class="panel merged">${b.merged.map(m => html`<div>
                <a href=${m.url} target="_blank" rel="noopener">${m.title}</a>
                <span class="note">${m.repo} #${m.pr}, ${m.queued ? 'by the queue' : 'by you'} ${ago(m.at)}</span></div>`)}</div>
        </section>`;
    }

    _row(row) {
        const pr = row.pr, f = row.flow;
        return html`<li>
            ${prIcon(pr)}
            <div class="main">
                <a class="title" href=${pr.url} target="_blank" rel="noopener">${pr.title}</a>
                <div class="meta">
                    <span>${pr.repo} #${pr.number}</span>
                    ${baseChip(pr)}
                    ${bumpChip(row)}
                    ${this._ciChip(pr)}
                    ${checkChip(row)}
                </div>
                ${this._ciOpen === key(pr) ? this._ciPanel(pr) : ''}
                <div class="meta">
                    ${this._stepper(f)}
                    <span class="say">${f.say}</span>
                    ${f.since ? html`<span>${ago(f.since)}</span>` : ''}
                </div>
                ${this._rowError?.key === key(pr) ? html`<p class="error row-error" role="alert">${this._rowError.text}
                    <button class="quiet sm" @click=${() => { this._rowError = null; }}>Dismiss</button></p>` : ''}
            </div>
            <div class="acts">${this._actions(row)}</div>
        </li>`;
    }

    /** Failing CI is a button, as on the GitHub board: what failed, and what to do about it. */
    _ciChip(pr) {
        if (pr.ci !== 'FAILURE' && pr.ci !== 'ERROR') return ciChip(pr);
        const open = this._ciOpen === key(pr);
        return html`<button class="chip bad" aria-expanded=${open ? 'true' : 'false'} title="Show what failed and what to do"
            @click=${() => this._toggleCi(pr)}>CI failing ▾</button>`;
    }

    async _toggleCi(pr) {
        const k = key(pr);
        this._ciError = null;
        if (this._ciOpen === k) {
            this._ciOpen = null;
            return;
        }
        this._ciOpen = k;
        this._ci = null;
        this._ciBusy = 'load';
        try {
            this._ci = await elf.act('ci-report', { repo: pr.repo, pr: pr.number });
        } catch (e) {
            if (this._ciOpen === k) this._ciError = e.message;
        } finally {
            this._ciBusy = null;
        }
    }

    async _ciDo(pr, what) {
        this._ciBusy = what;
        this._ciError = null;
        try {
            if (what === 'retry') await elf.act('ci-rerun', { repo: pr.repo, pr: pr.number });
            else if (what === 'rebase') await elf.act('dependabot-rebase', { repo: pr.repo, pr: pr.number });
            else await elf.openElf('github', `thread/${pr.repo}/${pr.number}`);
            if (what !== 'why') {
                this._ciOpen = null;
                this._load();
            }
        } catch (e) {
            if (!e.cancelled) this._ciError = what === 'why' ? `The GitHub elf isn't on your desk: add it from the + page to ask its agent.` : e.message;
        } finally {
            this._ciBusy = null;
        }
    }

    _ciPanel(pr) {
        const r = this._ci;
        const spin = what => this._ciBusy === what ? spinner : '';
        return html`<div class="panel-ci" role="group" aria-label="Failing CI">
            ${this._ciBusy === 'load' ? html`<span class="note">${spinner} Reading the checks…</span>` : ''}
            ${r ? html`
                ${r.running?.length ? html`<p class="note">${spinner} Running again now: ${r.running.join(', ')}.</p>` : ''}
                ${r.failed.length ? html`<ul>${r.failed.map(f => html`<li>
                    <a href=${webUrl(f.url) ?? `${pr.url}/checks`} target="_blank" rel="noopener">${f.name}</a>
                    <span class="note">${f.conclusion.toLowerCase().replace('_', ' ')}</span></li>`)}</ul>`
                    : html`<p class="note">GitHub reports no failed checks any more. CI may already be running again.</p>`}
                <div class="acts">
                    ${r.runs.length && r.canRerun ? html`<button class="sm primary" ?disabled=${!!this._ciBusy} @click=${() => this._ciDo(pr, 'retry')}>
                        ${spin('retry')}Retry failed builds</button>` : ''}
                    <button class="sm" ?disabled=${!!this._ciBusy} @click=${() => this._ciDo(pr, 'rebase')}
                        title="Dependabot rebases it on the newest base, which runs CI again">${spin('rebase')}Ask Dependabot to rebase</button>
                    <button class="sm" ?disabled=${!!this._ciBusy} @click=${() => this._ciDo(pr, 'why')}
                        title="Opens this PR in the GitHub elf, where its agent can say why CI fails, or fix it">Why is this failing</button>
                </div>
                ${!r.canRerun ? html`<p class="note">You don't have write access to ${pr.repo}, so GitHub won't let you re-run its
                    workflows. A rebase runs CI again.</p>` : ''}
            ` : ''}
            ${this._ciError ? html`<p class="error" role="alert">${this._ciError}</p>` : ''}
        </div>`;
    }

    _stepper(f) {
        if (!f.steps?.length) return '';
        return html`<span class="steps" title=${f.steps.join(' → ')}>
            <span class="seg" aria-hidden="true">${f.steps.map((_, i) => html`<i class=${i < f.step ? 'done' : i === f.step ? 'now' : ''}></i>`)}</span>
            <span class="stage">${f.steps[f.step]}</span></span>`;
    }

    _actions(row) {
        const f = row.flow;
        const steps = [f.next, ...f.more].filter(Boolean);
        return html`${steps.map((n, i) => this._action(row, n, i === 0 && n === f.next))}
            ${steps.some(n => n.id === 'github') ? '' : html`<a class="btn sm quiet" href=${row.pr.url} target="_blank" rel="noopener">On GitHub</a>`}`;
    }

    _action(row, n, primary) {
        const cls = primary ? 'sm primary' : 'sm quiet';
        const pr = row.pr;
        switch (n.id) {
            case 'github':
                return html`<a class="btn ${cls}" href=${pr.url} target="_blank" rel="noopener">${n.label}</a>`;
            case 'checks':
                return html`<a class="btn ${cls}" href="${pr.url}/checks" target="_blank" rel="noopener">${n.label}</a>`;
            default: {
                const busy = this._doing === `${key(pr)} ${n.id}`;
                return html`<button class=${cls} ?disabled=${this._doing !== null} @click=${() => this._step(row, n)}>
                    ${busy ? spinner : ''}${n.label}</button>`;
            }
        }
    }
}

/** The dashboard card: how many PRs, how many are ready, what the queue is doing, attention when one is your move. */
class DependabotCard extends LitElement {

    static properties = { _board: { state: true }, _state: { state: true }, _loaded: { state: true } };
    static styles = [shared, cardStyles];

    constructor() {
        super();
        new Ticking(this);
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
        this._off = elf.onChange(() => this._load());
    }

    disconnectedCallback() {
        this._off?.();
        super.disconnectedCallback();
    }

    async _load() {
        try {
            this._board = await board();
            this._state = await elf.state();
        } catch {
            this._board = null;
        }
        this._loaded = true;
        elf.card({ attention: (this._board?.rows ?? []).some(r => r.flow.waiting === 'you' || r.flow.waiting === 'ready') });
    }

    render() {
        if (!this._loaded) return html`<div class="skeleton say-sk"></div>`;
        const b = this._board;
        let say;
        if (!b) {
            say = this._state?.running?.board ? 'Reading the open Dependabot PRs from GitHub for the first time.'
                : 'No PRs read yet. Refresh them from its tab.';
        } else if (!b.rows.length) {
            say = 'No open Dependabot PRs.';
        } else {
            const yours = b.rows.filter(r => r.flow.waiting === 'you').length;
            const queued = b.rows.filter(r => r.flow.waiting === 'queue').length;
            const ready = b.rows.filter(r => r.flow.waiting === 'ready').length;
            const merged = (b.merged ?? []).filter(m => Date.now() - Date.parse(m.at) < 86400000).length;
            say = html`<a href="#">${count(b.rows.length, 'Dependabot PR')}</a>${ready ? html`, ${ready} ready to merge` : ''}${
                queued ? html`, ${queued} in the merge queue` : ''}${merged ? html`, ${merged} merged today` : ''}${
                yours ? html`. ${yours} ${yours === 1 ? 'needs' : 'need'} you.` : ''}`;
        }
        const failed = !this._state?.running?.board && this._state?.failed?.board;
        return html`<p class="say">${say}</p>
            <div class="meta">${b ? html`<span>Updated ${ago(b.fetchedAt)}</span>` : ''}${this._state?.running?.board
                ? html`<span class="running" role="status">${spinner}Refreshing</span>`
                : failed ? html`<span class="error">The last refresh failed</span>` : ''}</div>`;
    }
}

customElements.define('dependabot-tab', DependabotTab);
customElements.define('dependabot-card', DependabotCard);
document.body.append(document.createElement(elf.view === 'card' ? 'dependabot-card' : 'dependabot-tab'));
