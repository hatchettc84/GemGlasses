import { NavLink, Route, Routes } from "react-router-dom";
import { PeoplePage } from "./pages/PeoplePage";

export function App() {
  return (
    <div className="shell">
      <header>
        <strong>BobAI Console</strong>
        <nav>
          <NavLink to="/">People</NavLink>
        </nav>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<PeoplePage />} />
        </Routes>
      </main>
    </div>
  );
}
