import { type FormEvent, useEffect, useState } from 'react';
import {
  type AdminAssignment,
  ApiError,
  type AssignmentCategory,
  createTeacherAssignment,
  createTeacherCategory,
  deleteTeacherAssignment,
  deleteTeacherCategory,
  getTeacherAssignments,
  getTeacherCategories,
  getTeacherRoster,
  getTeacherSections,
  type RosterStudent,
  type TeacherSection,
  updateTeacherAssignment,
  updateTeacherCategory,
  upsertTeacherAssignmentGrade,
  upsertTeacherTermGrade,
} from '../api/client';

const GRADE_STATUSES = ['graded', 'missing', 'late', 'excused', 'pending'];

export default function Teacher() {
  const [sections, setSections] = useState<TeacherSection[] | null>(null);
  const [sectionId, setSectionId] = useState('');
  const [roster, setRoster] = useState<RosterStudent[]>([]);
  const [categories, setCategories] = useState<AssignmentCategory[]>([]);
  const [assignments, setAssignments] = useState<AdminAssignment[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    getTeacherSections()
      .then((s) => {
        setSections(s);
        if (s.length > 0) {
          setSectionId(s[0].id);
        }
      })
      .catch((err) => setError(err instanceof ApiError ? err.message : 'Failed to load your sections'));
  }, []);

  function loadSectionData(id: string) {
    if (!id) {
      setRoster([]);
      setCategories([]);
      setAssignments([]);
      return;
    }
    Promise.all([getTeacherRoster(id), getTeacherCategories(id), getTeacherAssignments(id)])
      .then(([r, c, a]) => {
        setRoster(r);
        setCategories(c);
        setAssignments(a);
      })
      .catch((err) => setError(err instanceof ApiError ? err.message : 'Failed to load class data'));
  }

  useEffect(() => loadSectionData(sectionId), [sectionId]);

  const selectedSection = sections?.find((s) => s.id === sectionId) ?? null;

  function handleError(err: unknown, fallback: string) {
    setNotice(null);
    setError(err instanceof ApiError ? err.message : fallback);
  }

  // --- Category form ---
  const [categoryName, setCategoryName] = useState('');
  const [categoryWeight, setCategoryWeight] = useState('');
  const [editingCategoryId, setEditingCategoryId] = useState<string | null>(null);

  function startEditCategory(c: AssignmentCategory) {
    setEditingCategoryId(c.id);
    setCategoryName(c.name);
    setCategoryWeight(String(c.weightPct));
  }

  function cancelEditCategory() {
    setEditingCategoryId(null);
    setCategoryName('');
    setCategoryWeight('');
  }

  async function submitCategory(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      const body = { name: categoryName, weightPct: Number(categoryWeight) };
      if (editingCategoryId) {
        await updateTeacherCategory(sectionId, editingCategoryId, body);
      } else {
        await createTeacherCategory(sectionId, body);
      }
      cancelEditCategory();
      loadSectionData(sectionId);
    } catch (err) {
      handleError(err, editingCategoryId ? 'Failed to update category' : 'Failed to create category');
    }
  }

  async function handleDeleteCategory(id: string) {
    setError(null);
    try {
      await deleteTeacherCategory(sectionId, id);
      if (editingCategoryId === id) cancelEditCategory();
      loadSectionData(sectionId);
    } catch (err) {
      handleError(err, 'Failed to delete category');
    }
  }

  // --- Assignment form ---
  const [assignmentCategoryId, setAssignmentCategoryId] = useState('');
  const [assignmentName, setAssignmentName] = useState('');
  const [assignmentPoints, setAssignmentPoints] = useState('');
  const [assignmentDue, setAssignmentDue] = useState('');
  const [editingAssignmentId, setEditingAssignmentId] = useState<string | null>(null);

  function startEditAssignment(a: AdminAssignment) {
    setEditingAssignmentId(a.id);
    const category = categories.find((c) => c.name === a.categoryName);
    setAssignmentCategoryId(category?.id ?? '');
    setAssignmentName(a.name);
    setAssignmentPoints(String(a.pointsPossible));
    setAssignmentDue(a.dueDate ?? '');
  }

  function cancelEditAssignment() {
    setEditingAssignmentId(null);
    setAssignmentCategoryId('');
    setAssignmentName('');
    setAssignmentPoints('');
    setAssignmentDue('');
  }

  async function submitAssignment(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      const body = {
        categoryId: assignmentCategoryId,
        name: assignmentName,
        pointsPossible: Number(assignmentPoints),
        dueDate: assignmentDue || undefined,
      };
      if (editingAssignmentId) {
        await updateTeacherAssignment(sectionId, editingAssignmentId, body);
      } else {
        await createTeacherAssignment(sectionId, body);
      }
      cancelEditAssignment();
      loadSectionData(sectionId);
    } catch (err) {
      handleError(err, editingAssignmentId ? 'Failed to update assignment' : 'Failed to create assignment');
    }
  }

  async function handleDeleteAssignment(id: string) {
    setError(null);
    try {
      await deleteTeacherAssignment(sectionId, id);
      if (editingAssignmentId === id) cancelEditAssignment();
      loadSectionData(sectionId);
    } catch (err) {
      handleError(err, 'Failed to delete assignment');
    }
  }

  // --- Score entry ---
  const [scoreAssignmentId, setScoreAssignmentId] = useState('');
  const [scoreStudentId, setScoreStudentId] = useState('');
  const [scorePoints, setScorePoints] = useState('');
  const [scoreStatus, setScoreStatus] = useState('graded');

  async function submitScore(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setNotice(null);
    try {
      await upsertTeacherAssignmentGrade(scoreAssignmentId, scoreStudentId, {
        pointsEarned: scorePoints ? Number(scorePoints) : undefined,
        status: scoreStatus,
      });
      setNotice('Score saved.');
    } catch (err) {
      handleError(err, 'Failed to save score');
    }
  }

  // --- Term grade ---
  const [tgStudentId, setTgStudentId] = useState('');
  const [tgFinalPct, setTgFinalPct] = useState('');
  const [tgFinalLetter, setTgFinalLetter] = useState('');
  const [tgPosted, setTgPosted] = useState(false);

  async function submitTermGrade(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setNotice(null);
    if (!selectedSection) return;
    try {
      await upsertTeacherTermGrade(sectionId, tgStudentId, {
        termId: selectedSection.termId,
        finalPct: tgFinalPct ? Number(tgFinalPct) : undefined,
        finalLetter: tgFinalLetter || undefined,
        posted: tgPosted,
      });
      setNotice('Term grade saved.');
    } catch (err) {
      handleError(err, 'Failed to save term grade');
    }
  }

  if (sections === null) {
    return <p>Loading…</p>;
  }

  if (sections.length === 0) {
    return (
      <div className="dashboard">
        <h2>No classes yet</h2>
        <p>Once an admin assigns you a section, it'll show up here.</p>
      </div>
    );
  }

  return (
    <div className="admin-grid">
      {error && <p className="auth-error">{error}</p>}
      {notice && <p className="admin-notice">{notice}</p>}

      <div className="field admin-section-picker">
        <label htmlFor="mySection">My courses</label>
        <select id="mySection" value={sectionId} onChange={(e) => setSectionId(e.target.value)}>
          {sections.map((s) => (
            <option key={s.id} value={s.id}>
              {s.courseName} ({s.termName}
              {s.period ? ` · ${s.period}` : ''})
            </option>
          ))}
        </select>
      </div>

      <section className="admin-panel">
        <h3>Roster</h3>
        <ul className="rule-list">
          {roster.length === 0 && <li className="rule-item">No students enrolled yet.</li>}
          {roster.map((r) => (
            <li key={r.id} className="rule-item">
              <p className="rule-trigger">
                {r.firstName} {r.lastName}
              </p>
            </li>
          ))}
        </ul>
      </section>

      <section className="admin-panel">
        <h3>Assignment categories</h3>
        <ul className="rule-list">
          {categories.map((c) => (
            <li key={c.id} className="rule-item">
              <div>
                <p className="rule-trigger">{c.name}</p>
                <p className="rule-detail">{c.weightPct}% of grade</p>
              </div>
              <div className="rule-actions">
                <button type="button" className="link-button" onClick={() => startEditCategory(c)}>
                  Edit
                </button>
                <button type="button" className="link-button" onClick={() => handleDeleteCategory(c.id)}>
                  Delete
                </button>
              </div>
            </li>
          ))}
        </ul>
        <form className="rule-form" onSubmit={submitCategory}>
          <div className="field">
            <label htmlFor="categoryName">Name</label>
            <input id="categoryName" value={categoryName} onChange={(e) => setCategoryName(e.target.value)} required />
          </div>
          <div className="field">
            <label htmlFor="categoryWeight">Weight (%)</label>
            <input
              id="categoryWeight"
              type="number"
              min={0}
              max={100}
              value={categoryWeight}
              onChange={(e) => setCategoryWeight(e.target.value)}
              required
            />
          </div>
          <button className="primary" type="submit">
            {editingCategoryId ? 'Save changes' : 'Add category'}
          </button>
          {editingCategoryId && (
            <button type="button" className="link-button" onClick={cancelEditCategory}>
              Cancel
            </button>
          )}
        </form>
      </section>

      <section className="admin-panel">
        <h3>Assignments &amp; tests</h3>
        <ul className="rule-list">
          {assignments.map((a) => (
            <li key={a.id} className="rule-item">
              <div>
                <p className="rule-trigger">{a.name}</p>
                <p className="rule-detail">
                  {a.categoryName} · {a.pointsPossible} pts{a.dueDate && ` · due ${a.dueDate}`}
                </p>
              </div>
              <div className="rule-actions">
                <button type="button" className="link-button" onClick={() => startEditAssignment(a)}>
                  Edit
                </button>
                <button type="button" className="link-button" onClick={() => handleDeleteAssignment(a.id)}>
                  Delete
                </button>
              </div>
            </li>
          ))}
        </ul>
        <form className="rule-form" onSubmit={submitAssignment}>
          <div className="field">
            <label htmlFor="assignmentCategoryId">Category</label>
            <select
              id="assignmentCategoryId"
              value={assignmentCategoryId}
              onChange={(e) => setAssignmentCategoryId(e.target.value)}
              required
            >
              <option value="" disabled>
                Select a category
              </option>
              {categories.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="assignmentName">Name</label>
            <input id="assignmentName" value={assignmentName} onChange={(e) => setAssignmentName(e.target.value)} required />
          </div>
          <div className="field">
            <label htmlFor="assignmentPoints">Points possible</label>
            <input
              id="assignmentPoints"
              type="number"
              min={0}
              value={assignmentPoints}
              onChange={(e) => setAssignmentPoints(e.target.value)}
              required
            />
          </div>
          <div className="field">
            <label htmlFor="assignmentDue">Due date (optional)</label>
            <input id="assignmentDue" type="date" value={assignmentDue} onChange={(e) => setAssignmentDue(e.target.value)} />
          </div>
          <button className="primary" type="submit">
            {editingAssignmentId ? 'Save changes' : 'Add assignment'}
          </button>
          {editingAssignmentId && (
            <button type="button" className="link-button" onClick={cancelEditAssignment}>
              Cancel
            </button>
          )}
        </form>
      </section>

      <section className="admin-panel">
        <h3>Enter a score</h3>
        <form className="rule-form" onSubmit={submitScore}>
          <div className="field">
            <label htmlFor="scoreAssignmentId">Assignment</label>
            <select id="scoreAssignmentId" value={scoreAssignmentId} onChange={(e) => setScoreAssignmentId(e.target.value)} required>
              <option value="" disabled>
                Select an assignment
              </option>
              {assignments.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name} ({a.pointsPossible} pts)
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="scoreStudentId">Student</label>
            <select id="scoreStudentId" value={scoreStudentId} onChange={(e) => setScoreStudentId(e.target.value)} required>
              <option value="" disabled>
                Select a student
              </option>
              {roster.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.firstName} {r.lastName}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="scorePoints">Points earned</label>
            <input id="scorePoints" type="number" min={0} value={scorePoints} onChange={(e) => setScorePoints(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="scoreStatus">Status</label>
            <select id="scoreStatus" value={scoreStatus} onChange={(e) => setScoreStatus(e.target.value)}>
              {GRADE_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </div>
          <button className="primary" type="submit">
            Save score
          </button>
        </form>
      </section>

      <section className="admin-panel">
        <h3>Post a term grade</h3>
        <form className="rule-form" onSubmit={submitTermGrade}>
          <div className="field">
            <label htmlFor="tgStudentId">Student</label>
            <select id="tgStudentId" value={tgStudentId} onChange={(e) => setTgStudentId(e.target.value)} required>
              <option value="" disabled>
                Select a student
              </option>
              {roster.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.firstName} {r.lastName}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="tgFinalPct">Final percentage</label>
            <input id="tgFinalPct" type="number" min={0} max={100} value={tgFinalPct} onChange={(e) => setTgFinalPct(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="tgFinalLetter">Final letter grade</label>
            <input id="tgFinalLetter" value={tgFinalLetter} onChange={(e) => setTgFinalLetter(e.target.value)} placeholder="A-" />
          </div>
          <label className="admin-checkbox">
            <input type="checkbox" checked={tgPosted} onChange={(e) => setTgPosted(e.target.checked)} />
            Post to parents now
          </label>
          <button className="primary" type="submit">
            Save term grade
          </button>
        </form>
      </section>
    </div>
  );
}
