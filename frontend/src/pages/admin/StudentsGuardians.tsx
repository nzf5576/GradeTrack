import { type FormEvent, useEffect, useState } from 'react';
import {
  type AdminStudent,
  ApiError,
  createAdminStudent,
  createEnrollment,
  deleteAdminStudent,
  deleteEnrollment,
  type Enrollment,
  getAdminStudents,
  getEnrollments,
  getGuardians,
  getSections,
  type Guardian,
  linkGuardian,
  revokeGuardian,
  type Section,
  updateAdminStudent,
} from '../../api/client';

export default function StudentsGuardians() {
  const [students, setStudents] = useState<AdminStudent[]>([]);
  const [sections, setSections] = useState<Section[]>([]);
  const [enrollments, setEnrollments] = useState<Enrollment[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  function loadAll() {
    Promise.all([getAdminStudents(), getSections(), getEnrollments()])
      .then(([s, sec, e]) => {
        setStudents(s);
        setSections(sec);
        setEnrollments(e);
      })
      .catch((err) => setError(err instanceof ApiError ? err.message : 'Failed to load data'));
  }

  useEffect(loadAll, []);

  function handleError(err: unknown, fallback: string) {
    setNotice(null);
    setError(err instanceof ApiError ? err.message : fallback);
  }

  // --- Students ---
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [dateOfBirth, setDateOfBirth] = useState('');
  const [editingStudentId, setEditingStudentId] = useState<string | null>(null);

  function startEditStudent(s: AdminStudent) {
    setEditingStudentId(s.id);
    setFirstName(s.firstName);
    setLastName(s.lastName);
    setDateOfBirth(s.dateOfBirth ?? '');
  }

  function cancelEditStudent() {
    setEditingStudentId(null);
    setFirstName('');
    setLastName('');
    setDateOfBirth('');
  }

  async function submitStudent(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      const body = { firstName, lastName, dateOfBirth: dateOfBirth || undefined };
      if (editingStudentId) {
        await updateAdminStudent(editingStudentId, body);
      } else {
        await createAdminStudent(body);
      }
      cancelEditStudent();
      loadAll();
    } catch (err) {
      handleError(err, editingStudentId ? 'Failed to update student' : 'Failed to create student');
    }
  }

  async function handleDeleteStudent(id: string) {
    setError(null);
    try {
      await deleteAdminStudent(id);
      if (editingStudentId === id) cancelEditStudent();
      loadAll();
    } catch (err) {
      handleError(err, 'Failed to delete student');
    }
  }

  // --- Link / revoke guardians ---
  const [guardianStudentId, setGuardianStudentId] = useState('');
  const [guardianEmail, setGuardianEmail] = useState('');
  const [relationship, setRelationship] = useState('parent');
  const [guardians, setGuardians] = useState<Guardian[]>([]);

  function loadGuardians(studentId: string) {
    if (!studentId) {
      setGuardians([]);
      return;
    }
    getGuardians(studentId)
      .then(setGuardians)
      .catch((err) => handleError(err, 'Failed to load guardians'));
  }

  useEffect(() => loadGuardians(guardianStudentId), [guardianStudentId]);

  async function submitGuardian(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setNotice(null);
    try {
      await linkGuardian(guardianStudentId, { guardianEmail, relationship });
      setGuardianEmail('');
      setNotice('Guardian linked.');
      loadGuardians(guardianStudentId);
    } catch (err) {
      handleError(err, 'Failed to link guardian');
    }
  }

  async function handleRevokeGuardian(guardianId: string) {
    setError(null);
    setNotice(null);
    try {
      await revokeGuardian(guardianStudentId, guardianId);
      loadGuardians(guardianStudentId);
    } catch (err) {
      handleError(err, 'Failed to revoke guardian access');
    }
  }

  // --- Enrollment ---
  const [enrollStudentId, setEnrollStudentId] = useState('');
  const [enrollSectionId, setEnrollSectionId] = useState('');

  async function submitEnrollment(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      await createEnrollment({ studentId: enrollStudentId, sectionId: enrollSectionId });
      loadAll();
    } catch (err) {
      handleError(err, 'Failed to enroll student');
    }
  }

  async function handleUnenroll(id: string) {
    setError(null);
    try {
      await deleteEnrollment(id);
      loadAll();
    } catch (err) {
      handleError(err, 'Failed to unenroll student');
    }
  }

  return (
    <div className="admin-grid">
      {error && <p className="auth-error">{error}</p>}
      {notice && <p className="admin-notice">{notice}</p>}

      <section className="admin-panel">
        <h3>Students</h3>
        <ul className="rule-list">
          {students.map((s) => (
            <li key={s.id} className="rule-item">
              <div>
                <p className="rule-trigger">
                  {s.firstName} {s.lastName}
                </p>
                {s.schoolName && <p className="rule-detail">{s.schoolName}</p>}
              </div>
              <div className="rule-actions">
                <button type="button" className="link-button" onClick={() => startEditStudent(s)}>
                  Edit
                </button>
                <button type="button" className="link-button" onClick={() => handleDeleteStudent(s.id)}>
                  Delete
                </button>
              </div>
            </li>
          ))}
        </ul>
        <form className="rule-form" onSubmit={submitStudent}>
          <div className="field">
            <label htmlFor="firstName">First name</label>
            <input id="firstName" value={firstName} onChange={(e) => setFirstName(e.target.value)} required />
          </div>
          <div className="field">
            <label htmlFor="lastName">Last name</label>
            <input id="lastName" value={lastName} onChange={(e) => setLastName(e.target.value)} required />
          </div>
          <div className="field">
            <label htmlFor="dateOfBirth">Date of birth (optional)</label>
            <input id="dateOfBirth" type="date" value={dateOfBirth} onChange={(e) => setDateOfBirth(e.target.value)} />
          </div>
          <button className="primary" type="submit">
            {editingStudentId ? 'Save changes' : 'Add student'}
          </button>
          {editingStudentId && (
            <button type="button" className="link-button" onClick={cancelEditStudent}>
              Cancel
            </button>
          )}
        </form>
      </section>

      <section className="admin-panel">
        <h3>Guardians</h3>
        <p className="rule-detail">The guardian must already have a GradeTrack account (they sign up like any parent).</p>
        <div className="field">
          <label htmlFor="guardianStudentId">Student</label>
          <select id="guardianStudentId" value={guardianStudentId} onChange={(e) => setGuardianStudentId(e.target.value)}>
            <option value="" disabled>
              Select a student
            </option>
            {students.map((s) => (
              <option key={s.id} value={s.id}>
                {s.firstName} {s.lastName}
              </option>
            ))}
          </select>
        </div>
        {guardianStudentId && (
          <ul className="rule-list">
            {guardians.length === 0 && (
              <li className="rule-item">
                <p className="rule-detail">No guardians linked yet.</p>
              </li>
            )}
            {guardians.map((g) => (
              <li key={g.guardianId} className="rule-item">
                <div>
                  <p className="rule-trigger">{g.guardianEmail}</p>
                  <p className="rule-detail">
                    {g.relationship}
                    {g.isPrimary && ' · primary'} · {g.accessLevel}
                  </p>
                </div>
                <div className="rule-actions">
                  <button type="button" className="link-button" onClick={() => handleRevokeGuardian(g.guardianId)}>
                    Revoke
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
        <form className="rule-form" onSubmit={submitGuardian}>
          <div className="field">
            <label htmlFor="guardianEmail">Guardian email</label>
            <input
              id="guardianEmail"
              type="email"
              value={guardianEmail}
              onChange={(e) => setGuardianEmail(e.target.value)}
              required
            />
          </div>
          <div className="field">
            <label htmlFor="relationship">Relationship</label>
            <select id="relationship" value={relationship} onChange={(e) => setRelationship(e.target.value)}>
              <option value="parent">Parent</option>
              <option value="guardian">Guardian</option>
              <option value="other">Other</option>
            </select>
          </div>
          <button className="primary" type="submit" disabled={!guardianStudentId}>
            Link guardian
          </button>
        </form>
      </section>

      <section className="admin-panel">
        <h3>Enrollment</h3>
        <ul className="rule-list">
          {enrollments.map((e) => (
            <li key={e.id} className="rule-item">
              <div>
                <p className="rule-trigger">{e.studentName}</p>
                <p className="rule-detail">
                  {e.courseName} · {e.status}
                </p>
              </div>
              <div className="rule-actions">
                <button type="button" className="link-button" onClick={() => handleUnenroll(e.id)}>
                  Unenroll
                </button>
              </div>
            </li>
          ))}
        </ul>
        <form className="rule-form" onSubmit={submitEnrollment}>
          <div className="field">
            <label htmlFor="enrollStudentId">Student</label>
            <select id="enrollStudentId" value={enrollStudentId} onChange={(e) => setEnrollStudentId(e.target.value)} required>
              <option value="" disabled>
                Select a student
              </option>
              {students.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.firstName} {s.lastName}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="enrollSectionId">Section</label>
            <select id="enrollSectionId" value={enrollSectionId} onChange={(e) => setEnrollSectionId(e.target.value)} required>
              <option value="" disabled>
                Select a section
              </option>
              {sections.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.courseName} ({s.termName})
                </option>
              ))}
            </select>
          </div>
          <button className="primary" type="submit">
            Enroll
          </button>
        </form>
      </section>
    </div>
  );
}
