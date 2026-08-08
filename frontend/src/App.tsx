import { BrowserRouter, Link, Navigate, Route, Routes, useNavigate } from 'react-router-dom';
import RequireAuth from './auth/RequireAuth';
import RequireAdmin from './auth/RequireAdmin';
import RequireTeacher from './auth/RequireTeacher';
import { clearToken, getRole, getToken } from './api/client';
import Signup from './pages/Signup';
import Login from './pages/Login';
import Dashboard from './pages/Dashboard';
import Alerts from './pages/Alerts';
import Admin from './pages/Admin';
import Teacher from './pages/Teacher';

function Header() {
  const navigate = useNavigate();
  const authenticated = Boolean(getToken());
  const role = getRole();

  function handleLogout() {
    clearToken();
    navigate('/login');
  }

  return (
    <header className="app-header">
      <h1>GradeTrack</h1>
      {authenticated && (
        <nav className="app-nav">
          <Link to="/dashboard">Dashboard</Link>
          <Link to="/alerts">Alerts</Link>
          {role === 'teacher' && <Link to="/teacher">My Classes</Link>}
          {role === 'admin' && <Link to="/admin">Admin</Link>}
          <button type="button" onClick={handleLogout}>
            Log out
          </button>
        </nav>
      )}
    </header>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <div className="app-shell">
        <Header />
        <main className="app-main">
          <Routes>
            <Route path="/signup" element={<Signup />} />
            <Route path="/login" element={<Login />} />
            <Route
              path="/dashboard"
              element={
                <RequireAuth>
                  <Dashboard />
                </RequireAuth>
              }
            />
            <Route
              path="/alerts"
              element={
                <RequireAuth>
                  <Alerts />
                </RequireAuth>
              }
            />
            <Route
              path="/admin"
              element={
                <RequireAdmin>
                  <Admin />
                </RequireAdmin>
              }
            />
            <Route
              path="/teacher"
              element={
                <RequireTeacher>
                  <Teacher />
                </RequireTeacher>
              }
            />
            <Route path="*" element={<Navigate to="/login" replace />} />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
  );
}
