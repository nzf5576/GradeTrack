import { type FormEvent, useEffect, useState } from 'react';
import {
  type AcademicYear,
  ApiError,
  type Course,
  createAcademicYear,
  createCourse,
  createSchool,
  createSection,
  createTeacher,
  createTerm,
  deleteAcademicYear,
  deleteCourse,
  deleteSchool,
  deleteSection,
  deleteTeacher,
  deleteTerm,
  getAcademicYears,
  getCourses,
  getSchools,
  getSections,
  getTeachers,
  getTerms,
  linkTeacherAccount,
  type School,
  type Section,
  type Teacher,
  type Term,
  updateAcademicYear,
  updateCourse,
  updateSchool,
  updateSection,
  updateTeacher,
  updateTerm,
} from '../../api/client';

const TERM_TYPES = ['semester', 'quarter', 'trimester', 'full_year'];

export default function OrgSetup() {
  const [schools, setSchools] = useState<School[]>([]);
  const [academicYears, setAcademicYears] = useState<AcademicYear[]>([]);
  const [terms, setTerms] = useState<Term[]>([]);
  const [courses, setCourses] = useState<Course[]>([]);
  const [teachers, setTeachers] = useState<Teacher[]>([]);
  const [sections, setSections] = useState<Section[]>([]);
  const [error, setError] = useState<string | null>(null);

  function loadAll() {
    Promise.all([getSchools(), getAcademicYears(), getTerms(), getCourses(), getTeachers(), getSections()])
      .then(([s, ay, t, c, tc, sec]) => {
        setSchools(s);
        setAcademicYears(ay);
        setTerms(t);
        setCourses(c);
        setTeachers(tc);
        setSections(sec);
      })
      .catch((err) => setError(err instanceof ApiError ? err.message : 'Failed to load org data'));
  }

  useEffect(loadAll, []);

  function handleError(err: unknown, fallback: string) {
    setError(err instanceof ApiError ? err.message : fallback);
  }

  // --- Schools ---
  const [schoolName, setSchoolName] = useState('');
  const [schoolDistrict, setSchoolDistrict] = useState('');
  const [editingSchoolId, setEditingSchoolId] = useState<string | null>(null);

  function startEditSchool(s: School) {
    setEditingSchoolId(s.id);
    setSchoolName(s.name);
    setSchoolDistrict(s.districtName ?? '');
  }

  function cancelEditSchool() {
    setEditingSchoolId(null);
    setSchoolName('');
    setSchoolDistrict('');
  }

  async function submitSchool(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      const body = { name: schoolName, districtName: schoolDistrict || undefined };
      if (editingSchoolId) {
        await updateSchool(editingSchoolId, body);
      } else {
        await createSchool(body);
      }
      cancelEditSchool();
      loadAll();
    } catch (err) {
      handleError(err, editingSchoolId ? 'Failed to update school' : 'Failed to create school');
    }
  }

  async function handleDeleteSchool(id: string) {
    setError(null);
    try {
      await deleteSchool(id);
      if (editingSchoolId === id) cancelEditSchool();
      loadAll();
    } catch (err) {
      handleError(err, 'Failed to delete school');
    }
  }

  // --- Academic years ---
  const [ayCcSchoolId, setAyCcSchoolId] = useState('');
  const [ayName, setAyName] = useState('');
  const [ayStart, setAyStart] = useState('');
  const [ayEnd, setAyEnd] = useState('');
  const [editingAyId, setEditingAyId] = useState<string | null>(null);

  function startEditAy(ay: AcademicYear) {
    setEditingAyId(ay.id);
    setAyCcSchoolId(ay.schoolId);
    setAyName(ay.name);
    setAyStart(ay.startDate);
    setAyEnd(ay.endDate);
  }

  function cancelEditAy() {
    setEditingAyId(null);
    setAyCcSchoolId('');
    setAyName('');
    setAyStart('');
    setAyEnd('');
  }

  async function submitAcademicYear(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      const body = { schoolId: ayCcSchoolId, name: ayName, startDate: ayStart, endDate: ayEnd };
      if (editingAyId) {
        await updateAcademicYear(editingAyId, body);
      } else {
        await createAcademicYear(body);
      }
      cancelEditAy();
      loadAll();
    } catch (err) {
      handleError(err, editingAyId ? 'Failed to update academic year' : 'Failed to create academic year');
    }
  }

  async function handleDeleteAy(id: string) {
    setError(null);
    try {
      await deleteAcademicYear(id);
      if (editingAyId === id) cancelEditAy();
      loadAll();
    } catch (err) {
      handleError(err, 'Failed to delete academic year');
    }
  }

  // --- Terms ---
  const [termYearId, setTermYearId] = useState('');
  const [termName, setTermName] = useState('');
  const [termType, setTermType] = useState(TERM_TYPES[0]);
  const [termStart, setTermStart] = useState('');
  const [termEnd, setTermEnd] = useState('');
  const [editingTermId, setEditingTermId] = useState<string | null>(null);

  function startEditTerm(t: Term) {
    setEditingTermId(t.id);
    setTermYearId(t.academicYearId);
    setTermName(t.name);
    setTermType(t.termType);
    setTermStart(t.startDate);
    setTermEnd(t.endDate);
  }

  function cancelEditTerm() {
    setEditingTermId(null);
    setTermYearId('');
    setTermName('');
    setTermType(TERM_TYPES[0]);
    setTermStart('');
    setTermEnd('');
  }

  async function submitTerm(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      if (editingTermId) {
        await updateTerm(editingTermId, {
          name: termName,
          termType,
          startDate: termStart,
          endDate: termEnd,
        });
      } else {
        await createTerm({
          academicYearId: termYearId,
          name: termName,
          termType,
          startDate: termStart,
          endDate: termEnd,
        });
      }
      cancelEditTerm();
      loadAll();
    } catch (err) {
      handleError(err, editingTermId ? 'Failed to update term' : 'Failed to create term');
    }
  }

  async function handleDeleteTerm(id: string) {
    setError(null);
    try {
      await deleteTerm(id);
      if (editingTermId === id) cancelEditTerm();
      loadAll();
    } catch (err) {
      handleError(err, 'Failed to delete term');
    }
  }

  // --- Courses ---
  const [courseSchoolId, setCourseSchoolId] = useState('');
  const [courseName, setCourseName] = useState('');
  const [courseSubject, setCourseSubject] = useState('');
  const [editingCourseId, setEditingCourseId] = useState<string | null>(null);

  function startEditCourse(c: Course) {
    setEditingCourseId(c.id);
    setCourseSchoolId(c.schoolId);
    setCourseName(c.name);
    setCourseSubject(c.subject ?? '');
  }

  function cancelEditCourse() {
    setEditingCourseId(null);
    setCourseSchoolId('');
    setCourseName('');
    setCourseSubject('');
  }

  async function submitCourse(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      if (editingCourseId) {
        await updateCourse(editingCourseId, { name: courseName, subject: courseSubject || undefined });
      } else {
        await createCourse({ schoolId: courseSchoolId, name: courseName, subject: courseSubject || undefined });
      }
      cancelEditCourse();
      loadAll();
    } catch (err) {
      handleError(err, editingCourseId ? 'Failed to update course' : 'Failed to create course');
    }
  }

  async function handleDeleteCourse(id: string) {
    setError(null);
    try {
      await deleteCourse(id);
      if (editingCourseId === id) cancelEditCourse();
      loadAll();
    } catch (err) {
      handleError(err, 'Failed to delete course');
    }
  }

  // --- Teachers ---
  const [teacherSchoolId, setTeacherSchoolId] = useState('');
  const [teacherFirst, setTeacherFirst] = useState('');
  const [teacherLast, setTeacherLast] = useState('');
  const [teacherEmail, setTeacherEmail] = useState('');
  const [editingTeacherId, setEditingTeacherId] = useState<string | null>(null);

  function startEditTeacher(t: Teacher) {
    setEditingTeacherId(t.id);
    setTeacherSchoolId(t.schoolId);
    setTeacherFirst(t.firstName);
    setTeacherLast(t.lastName);
    setTeacherEmail(t.email ?? '');
  }

  function cancelEditTeacher() {
    setEditingTeacherId(null);
    setTeacherSchoolId('');
    setTeacherFirst('');
    setTeacherLast('');
    setTeacherEmail('');
  }

  async function submitTeacher(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      if (editingTeacherId) {
        await updateTeacher(editingTeacherId, {
          firstName: teacherFirst,
          lastName: teacherLast,
          email: teacherEmail || undefined,
        });
      } else {
        await createTeacher({
          schoolId: teacherSchoolId,
          firstName: teacherFirst,
          lastName: teacherLast,
          email: teacherEmail || undefined,
        });
      }
      cancelEditTeacher();
      loadAll();
    } catch (err) {
      handleError(err, editingTeacherId ? 'Failed to update teacher' : 'Failed to create teacher');
    }
  }

  async function handleDeleteTeacher(id: string) {
    setError(null);
    try {
      await deleteTeacher(id);
      if (editingTeacherId === id) cancelEditTeacher();
      loadAll();
    } catch (err) {
      handleError(err, 'Failed to delete teacher');
    }
  }

  // --- Link an existing account as a teacher's login ---
  const [linkTeacherId, setLinkTeacherId] = useState('');
  const [linkTeacherEmail, setLinkTeacherEmail] = useState('');
  const [notice, setNotice] = useState<string | null>(null);

  async function submitLinkTeacherAccount(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setNotice(null);
    try {
      await linkTeacherAccount(linkTeacherId, linkTeacherEmail);
      setLinkTeacherEmail('');
      setNotice('Account linked — they can log in as this teacher now.');
    } catch (err) {
      handleError(err, 'Failed to link account');
    }
  }

  // --- Sections ---
  const [sectionCourseId, setSectionCourseId] = useState('');
  const [sectionTermId, setSectionTermId] = useState('');
  const [sectionTeacherId, setSectionTeacherId] = useState('');
  const [sectionPeriod, setSectionPeriod] = useState('');
  const [sectionRoom, setSectionRoom] = useState('');
  const [editingSectionId, setEditingSectionId] = useState<string | null>(null);

  function startEditSection(s: Section) {
    setEditingSectionId(s.id);
    setSectionPeriod(s.period ?? '');
    setSectionRoom(s.room ?? '');
    const teacher = teachers.find((t) => `${t.firstName} ${t.lastName}` === s.teacherName);
    setSectionTeacherId(teacher?.id ?? '');
  }

  function cancelEditSection() {
    setEditingSectionId(null);
    setSectionCourseId('');
    setSectionTermId('');
    setSectionTeacherId('');
    setSectionPeriod('');
    setSectionRoom('');
  }

  async function submitSection(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      if (editingSectionId) {
        await updateSection(editingSectionId, {
          teacherId: sectionTeacherId || undefined,
          period: sectionPeriod || undefined,
          room: sectionRoom || undefined,
        });
      } else {
        await createSection({
          courseId: sectionCourseId,
          termId: sectionTermId,
          teacherId: sectionTeacherId,
          period: sectionPeriod || undefined,
          room: sectionRoom || undefined,
        });
      }
      cancelEditSection();
      loadAll();
    } catch (err) {
      handleError(err, editingSectionId ? 'Failed to update section' : 'Failed to create section');
    }
  }

  async function handleDeleteSection(id: string) {
    setError(null);
    try {
      await deleteSection(id);
      if (editingSectionId === id) cancelEditSection();
      loadAll();
    } catch (err) {
      handleError(err, 'Failed to delete section');
    }
  }

  return (
    <div className="admin-grid">
      {error && <p className="auth-error">{error}</p>}
      {notice && <p className="admin-notice">{notice}</p>}

      <section className="admin-panel">
        <h3>Schools</h3>
        <ul className="rule-list">
          {schools.map((s) => (
            <li key={s.id} className="rule-item">
              <div>
                <p className="rule-trigger">{s.name}</p>
                {s.districtName && <p className="rule-detail">{s.districtName}</p>}
              </div>
              <div className="rule-actions">
                <button type="button" className="link-button" onClick={() => startEditSchool(s)}>
                  Edit
                </button>
                <button type="button" className="link-button" onClick={() => handleDeleteSchool(s.id)}>
                  Delete
                </button>
              </div>
            </li>
          ))}
        </ul>
        <form className="rule-form" onSubmit={submitSchool}>
          <div className="field">
            <label htmlFor="schoolName">Name</label>
            <input id="schoolName" value={schoolName} onChange={(e) => setSchoolName(e.target.value)} required />
          </div>
          <div className="field">
            <label htmlFor="schoolDistrict">District (optional)</label>
            <input id="schoolDistrict" value={schoolDistrict} onChange={(e) => setSchoolDistrict(e.target.value)} />
          </div>
          <button className="primary" type="submit">
            {editingSchoolId ? 'Save changes' : 'Add school'}
          </button>
          {editingSchoolId && (
            <button type="button" className="link-button" onClick={cancelEditSchool}>
              Cancel
            </button>
          )}
        </form>
      </section>

      <section className="admin-panel">
        <h3>Academic years</h3>
        <ul className="rule-list">
          {academicYears.map((ay) => (
            <li key={ay.id} className="rule-item">
              <div>
                <p className="rule-trigger">{ay.name}</p>
                <p className="rule-detail">
                  {ay.schoolName} · {ay.startDate} – {ay.endDate}
                </p>
              </div>
              <div className="rule-actions">
                <button type="button" className="link-button" onClick={() => startEditAy(ay)}>
                  Edit
                </button>
                <button type="button" className="link-button" onClick={() => handleDeleteAy(ay.id)}>
                  Delete
                </button>
              </div>
            </li>
          ))}
        </ul>
        <form className="rule-form" onSubmit={submitAcademicYear}>
          <div className="field">
            <label htmlFor="ayCcSchoolId">School</label>
            <select
              id="ayCcSchoolId"
              value={ayCcSchoolId}
              onChange={(e) => setAyCcSchoolId(e.target.value)}
              disabled={Boolean(editingAyId)}
              required
            >
              <option value="" disabled>
                Select a school
              </option>
              {schools.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="ayName">Name</label>
            <input id="ayName" value={ayName} onChange={(e) => setAyName(e.target.value)} placeholder="2025-2026" required />
          </div>
          <div className="field">
            <label htmlFor="ayStart">Start date</label>
            <input id="ayStart" type="date" value={ayStart} onChange={(e) => setAyStart(e.target.value)} required />
          </div>
          <div className="field">
            <label htmlFor="ayEnd">End date</label>
            <input id="ayEnd" type="date" value={ayEnd} onChange={(e) => setAyEnd(e.target.value)} required />
          </div>
          <button className="primary" type="submit">
            {editingAyId ? 'Save changes' : 'Add academic year'}
          </button>
          {editingAyId && (
            <button type="button" className="link-button" onClick={cancelEditAy}>
              Cancel
            </button>
          )}
        </form>
      </section>

      <section className="admin-panel">
        <h3>Terms</h3>
        <ul className="rule-list">
          {terms.map((t) => (
            <li key={t.id} className="rule-item">
              <div>
                <p className="rule-trigger">{t.name}</p>
                <p className="rule-detail">
                  {t.academicYearName} · {t.termType} · {t.startDate} – {t.endDate}
                </p>
              </div>
              <div className="rule-actions">
                <button type="button" className="link-button" onClick={() => startEditTerm(t)}>
                  Edit
                </button>
                <button type="button" className="link-button" onClick={() => handleDeleteTerm(t.id)}>
                  Delete
                </button>
              </div>
            </li>
          ))}
        </ul>
        <form className="rule-form" onSubmit={submitTerm}>
          <div className="field">
            <label htmlFor="termYearId">Academic year</label>
            <select
              id="termYearId"
              value={termYearId}
              onChange={(e) => setTermYearId(e.target.value)}
              disabled={Boolean(editingTermId)}
              required
            >
              <option value="" disabled>
                Select an academic year
              </option>
              {academicYears.map((ay) => (
                <option key={ay.id} value={ay.id}>
                  {ay.name} ({ay.schoolName})
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="termName">Name</label>
            <input id="termName" value={termName} onChange={(e) => setTermName(e.target.value)} placeholder="Fall Semester" required />
          </div>
          <div className="field">
            <label htmlFor="termType">Type</label>
            <select id="termType" value={termType} onChange={(e) => setTermType(e.target.value)}>
              {TERM_TYPES.map((tt) => (
                <option key={tt} value={tt}>
                  {tt}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="termStart">Start date</label>
            <input id="termStart" type="date" value={termStart} onChange={(e) => setTermStart(e.target.value)} required />
          </div>
          <div className="field">
            <label htmlFor="termEnd">End date</label>
            <input id="termEnd" type="date" value={termEnd} onChange={(e) => setTermEnd(e.target.value)} required />
          </div>
          <button className="primary" type="submit">
            {editingTermId ? 'Save changes' : 'Add term'}
          </button>
          {editingTermId && (
            <button type="button" className="link-button" onClick={cancelEditTerm}>
              Cancel
            </button>
          )}
        </form>
      </section>

      <section className="admin-panel">
        <h3>Courses</h3>
        <ul className="rule-list">
          {courses.map((c) => (
            <li key={c.id} className="rule-item">
              <div>
                <p className="rule-trigger">{c.name}</p>
                {c.subject && <p className="rule-detail">{c.subject}</p>}
              </div>
              <div className="rule-actions">
                <button type="button" className="link-button" onClick={() => startEditCourse(c)}>
                  Edit
                </button>
                <button type="button" className="link-button" onClick={() => handleDeleteCourse(c.id)}>
                  Delete
                </button>
              </div>
            </li>
          ))}
        </ul>
        <form className="rule-form" onSubmit={submitCourse}>
          <div className="field">
            <label htmlFor="courseSchoolId">School</label>
            <select
              id="courseSchoolId"
              value={courseSchoolId}
              onChange={(e) => setCourseSchoolId(e.target.value)}
              disabled={Boolean(editingCourseId)}
              required
            >
              <option value="" disabled>
                Select a school
              </option>
              {schools.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="courseName">Name</label>
            <input id="courseName" value={courseName} onChange={(e) => setCourseName(e.target.value)} placeholder="Algebra I" required />
          </div>
          <div className="field">
            <label htmlFor="courseSubject">Subject (optional)</label>
            <input id="courseSubject" value={courseSubject} onChange={(e) => setCourseSubject(e.target.value)} />
          </div>
          <button className="primary" type="submit">
            {editingCourseId ? 'Save changes' : 'Add course'}
          </button>
          {editingCourseId && (
            <button type="button" className="link-button" onClick={cancelEditCourse}>
              Cancel
            </button>
          )}
        </form>
      </section>

      <section className="admin-panel">
        <h3>Teachers</h3>
        <ul className="rule-list">
          {teachers.map((t) => (
            <li key={t.id} className="rule-item">
              <div>
                <p className="rule-trigger">
                  {t.firstName} {t.lastName}
                </p>
                {t.email && <p className="rule-detail">{t.email}</p>}
              </div>
              <div className="rule-actions">
                <button type="button" className="link-button" onClick={() => startEditTeacher(t)}>
                  Edit
                </button>
                <button type="button" className="link-button" onClick={() => handleDeleteTeacher(t.id)}>
                  Delete
                </button>
              </div>
            </li>
          ))}
        </ul>
        <form className="rule-form" onSubmit={submitTeacher}>
          <div className="field">
            <label htmlFor="teacherSchoolId">School</label>
            <select
              id="teacherSchoolId"
              value={teacherSchoolId}
              onChange={(e) => setTeacherSchoolId(e.target.value)}
              disabled={Boolean(editingTeacherId)}
              required
            >
              <option value="" disabled>
                Select a school
              </option>
              {schools.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="teacherFirst">First name</label>
            <input id="teacherFirst" value={teacherFirst} onChange={(e) => setTeacherFirst(e.target.value)} required />
          </div>
          <div className="field">
            <label htmlFor="teacherLast">Last name</label>
            <input id="teacherLast" value={teacherLast} onChange={(e) => setTeacherLast(e.target.value)} required />
          </div>
          <div className="field">
            <label htmlFor="teacherEmail">Email (optional)</label>
            <input id="teacherEmail" type="email" value={teacherEmail} onChange={(e) => setTeacherEmail(e.target.value)} />
          </div>
          <button className="primary" type="submit">
            {editingTeacherId ? 'Save changes' : 'Add teacher'}
          </button>
          {editingTeacherId && (
            <button type="button" className="link-button" onClick={cancelEditTeacher}>
              Cancel
            </button>
          )}
        </form>

        <form className="rule-form" onSubmit={submitLinkTeacherAccount}>
          <div className="field">
            <label htmlFor="linkTeacherId">Give a teacher a login</label>
            <select id="linkTeacherId" value={linkTeacherId} onChange={(e) => setLinkTeacherId(e.target.value)} required>
              <option value="" disabled>
                Select a teacher
              </option>
              {teachers.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.firstName} {t.lastName}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="linkTeacherEmail">Their GradeTrack account email</label>
            <input
              id="linkTeacherEmail"
              type="email"
              value={linkTeacherEmail}
              onChange={(e) => setLinkTeacherEmail(e.target.value)}
              required
            />
            <p className="rule-detail">They need to already have signed up — this just links their existing account.</p>
          </div>
          <button className="primary" type="submit">
            Link account
          </button>
        </form>
      </section>

      <section className="admin-panel">
        <h3>Sections</h3>
        <ul className="rule-list">
          {sections.map((s) => (
            <li key={s.id} className="rule-item">
              <div>
                <p className="rule-trigger">{s.courseName}</p>
                <p className="rule-detail">
                  {s.termName} · {s.teacherName}
                  {s.period && ` · ${s.period}`}
                </p>
              </div>
              <div className="rule-actions">
                <button type="button" className="link-button" onClick={() => startEditSection(s)}>
                  Edit
                </button>
                <button type="button" className="link-button" onClick={() => handleDeleteSection(s.id)}>
                  Delete
                </button>
              </div>
            </li>
          ))}
        </ul>
        <form className="rule-form" onSubmit={submitSection}>
          <div className="field">
            <label htmlFor="sectionCourseId">Course</label>
            <select
              id="sectionCourseId"
              value={sectionCourseId}
              onChange={(e) => setSectionCourseId(e.target.value)}
              disabled={Boolean(editingSectionId)}
              required
            >
              <option value="" disabled>
                Select a course
              </option>
              {courses.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="sectionTermId">Term</label>
            <select
              id="sectionTermId"
              value={sectionTermId}
              onChange={(e) => setSectionTermId(e.target.value)}
              disabled={Boolean(editingSectionId)}
              required
            >
              <option value="" disabled>
                Select a term
              </option>
              {terms.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="sectionTeacherId">Teacher</label>
            <select id="sectionTeacherId" value={sectionTeacherId} onChange={(e) => setSectionTeacherId(e.target.value)} required>
              <option value="" disabled>
                Select a teacher
              </option>
              {teachers.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.firstName} {t.lastName}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="sectionPeriod">Period (optional)</label>
            <input id="sectionPeriod" value={sectionPeriod} onChange={(e) => setSectionPeriod(e.target.value)} placeholder="Period 3" />
          </div>
          <div className="field">
            <label htmlFor="sectionRoom">Room (optional)</label>
            <input id="sectionRoom" value={sectionRoom} onChange={(e) => setSectionRoom(e.target.value)} />
          </div>
          <button className="primary" type="submit">
            {editingSectionId ? 'Save changes' : 'Add section'}
          </button>
          {editingSectionId && (
            <button type="button" className="link-button" onClick={cancelEditSection}>
              Cancel
            </button>
          )}
        </form>
      </section>
    </div>
  );
}
