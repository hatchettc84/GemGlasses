import { Navigate, NavLink, Route, Routes } from "react-router-dom";
import { ManualsPage } from "./pages/ManualsPage";
import { PeoplePage } from "./pages/PeoplePage";

const APP_URL: string = import.meta.env.VITE_BOBAI_APP_URL || "http://127.0.0.1:8095";

export function App() {
  return (
    <div className="shell">
      <header>
        <strong>BobAI Admin</strong>
        <nav>
          <NavLink to="/" end>Phones &amp; people</NavLink>
          <NavLink to="/manuals">Manuals</NavLink>
        </nav>
        <a className="app-link" href={APP_URL}>Open BobAI →</a>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<PeoplePage />} />
          <Route path="/manuals" element={<ManualsPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  );
}
