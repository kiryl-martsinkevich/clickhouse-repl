import { useEffect, useRef, useState } from "react";
import { api, hasRole, setUnauthorisedHandler, type Me } from "./api";
import { completeLogin, loadConfig, login, logout, token, type PortalConfig } from "./auth";
import { GuardBanner, Notifier } from "./components";
import { AdminPage } from "./pages/Admin";
import { ContinuousPage } from "./pages/Continuous";
import { GuardPage } from "./pages/Guard";
import { HistoryPage } from "./pages/History";
import { JobDetailPage } from "./pages/JobDetail";
import { JobsPage } from "./pages/Jobs";
import { NewRequestPage } from "./pages/NewRequest";

function useHashRoute(): string {
  const [route, setRoute] = useState(location.hash || "#/new");
  useEffect(() => {
    const onChange = () => setRoute(location.hash || "#/new");
    window.addEventListener("hashchange", onChange);
    return () => window.removeEventListener("hashchange", onChange);
  }, []);
  return route;
}

export function App() {
  const [config, setConfig] = useState<PortalConfig | null>(null);
  const [me, setMe] = useState<Me | null>(null);
  const [error, setError] = useState<string | null>(null);
  const route = useHashRoute();
  const main = useRef<HTMLElement>(null);

  const started = useRef(false);

  useEffect(() => {
    // Runs once even under StrictMode's double effects: a sign-in code may be redeemed only once.
    if (started.current) return;
    started.current = true;
    (async () => {
      try {
        const cfg = await loadConfig();
        setConfig(cfg);
        setUnauthorisedHandler(() => void login(cfg));
        if (!token() && !(await completeLogin(cfg))) {
          await login(cfg);
          return;
        }
        setMe(await api.me());
      } catch (e) {
        setError((e as Error).message);
      }
    })();
  }, []);

  // Move focus to the page heading on navigation, for keyboard and screen-reader users.
  useEffect(() => {
    main.current?.querySelector("h1")?.focus();
  }, [route, me]);

  if (error) {
    return (
      <main className="container">
        <h1>clickhouse-repl</h1>
        <p className="error" role="alert">
          {error}
        </p>
      </main>
    );
  }
  if (!me || !config) {
    return (
      <main className="container">
        <p role="status">Signing in…</p>
      </main>
    );
  }

  const nav: [string, string, boolean][] = [
    ["#/new", "New request", hasRole(me, "requester")],
    ["#/jobs", "Jobs", true],
    ["#/history", "Target history", true],
    ["#/continuous", "Continuous", true],
    ["#/guard", "Source guard", true],
    ["#/admin", "Operations", hasRole(me, "operator") || hasRole(me, "admin")],
  ];
  const [, page, id] = route.split("/");
  let content;
  switch (page) {
    case "jobs":
      content = id ? <JobDetailPage id={decodeURIComponent(id)} me={me} /> : <JobsPage me={me} />;
      break;
    case "history":
      content = <HistoryPage />;
      break;
    case "continuous":
      content = <ContinuousPage me={me} />;
      break;
    case "guard":
      content = <GuardPage />;
      break;
    case "admin":
      content = <AdminPage me={me} />;
      break;
    default:
      content = hasRole(me, "requester") ? <NewRequestPage /> : <JobsPage me={me} />;
  }

  return (
    <>
      <a className="skip" href="#main" onClick={(e) => (e.preventDefault(), main.current?.querySelector("h1")?.focus())}>
        Skip to content
      </a>
      <header className="topbar">
        <span className="brand">clickhouse-repl</span>
        <nav aria-label="Main">
          <ul>
            {nav
              .filter(([, , visible]) => visible)
              .map(([href, label]) => (
                <li key={href}>
                  <a href={href} aria-current={route.startsWith(href) ? "page" : undefined}>
                    {label}
                  </a>
                </li>
              ))}
          </ul>
        </nav>
        <span className="who">
          {me.user} <span className="muted">({me.roles.filter((r) => r !== "viewer").join(", ") || "viewer"})</span>
          <button
            type="button"
            className="link"
            onClick={() => {
              logout();
              void login(config);
            }}
          >
            Sign out
          </button>
        </span>
      </header>
      <GuardBanner />
      <main id="main" ref={main} className="container">
        {content}
      </main>
      <Notifier />
    </>
  );
}
