import { NavLink, Route, Routes } from "react-router-dom";
import { JobsPage } from "./pages/JobsPage";
import { ManualsPage } from "./pages/ManualsPage";
import { NotesPage } from "./pages/NotesPage";
import { PeoplePage } from "./pages/PeoplePage";

export function App() {
  return (
    <div className="shell">
      <header>
        <strong>BobAI Console</strong>
        <nav>
          <NavLink to="/" end>People</NavLink>
          <NavLink to="/jobs">Jobs</NavLink>
          <NavLink to="/manuals">Manuals</NavLink>
          <NavLink to="/notes">Notes</NavLink>
        </nav>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<PeoplePage />} />
          <Route path="/jobs" element={<JobsPage />} />
          <Route path="/manuals" element={<ManualsPage />} />
          <Route path="/notes" element={<NotesPage />} />
        </Routes>
      </main>
    </div>
  );
}
