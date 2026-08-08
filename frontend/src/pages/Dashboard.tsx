import { useEffect, useState } from 'react';
import { ApiError, getStudentGrades, getStudents, type Student, type TermGrade } from '../api/client';

export default function Dashboard() {
  const [students, setStudents] = useState<Student[] | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [grades, setGrades] = useState<TermGrade[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loadingGrades, setLoadingGrades] = useState(false);

  useEffect(() => {
    getStudents()
      .then((result) => {
        setStudents(result);
        if (result.length > 0) {
          setSelectedId(result[0].id);
        }
      })
      .catch((err) => setError(err instanceof ApiError ? err.message : 'Failed to load students'));
  }, []);

  useEffect(() => {
    if (!selectedId) {
      return;
    }
    setLoadingGrades(true);
    setError(null);
    getStudentGrades(selectedId)
      .then(setGrades)
      .catch((err) => setError(err instanceof ApiError ? err.message : 'Failed to load grades'))
      .finally(() => setLoadingGrades(false));
  }, [selectedId]);

  if (error) {
    return <p className="auth-error">{error}</p>;
  }

  if (students === null) {
    return <p>Loading…</p>;
  }

  if (students.length === 0) {
    return (
      <div className="dashboard">
        <h2>No students yet</h2>
        <p>Once you're linked to a student, their grades will show up here.</p>
      </div>
    );
  }

  const selected = students.find((s) => s.id === selectedId) ?? students[0];

  return (
    <div className="dashboard-page">
      {students.length > 1 && (
        <div className="student-tabs">
          {students.map((student) => (
            <button
              key={student.id}
              type="button"
              className={student.id === selectedId ? 'student-tab active' : 'student-tab'}
              onClick={() => setSelectedId(student.id)}
            >
              {student.firstName}
            </button>
          ))}
        </div>
      )}

      <h2>
        {selected.firstName} {selected.lastName}
      </h2>
      {selected.schoolName && <p className="student-school">{selected.schoolName}</p>}

      {loadingGrades && <p>Loading grades…</p>}

      {!loadingGrades && grades && grades.length === 0 && <p>No grades posted yet.</p>}

      {!loadingGrades && grades && grades.length > 0 && (
        <table className="grades-table">
          <thead>
            <tr>
              <th>Course</th>
              <th>Term</th>
              <th>Teacher</th>
              <th>Grade</th>
            </tr>
          </thead>
          <tbody>
            {grades.map((grade, i) => (
              <tr key={i}>
                <td>{grade.courseName}</td>
                <td>{grade.termName}</td>
                <td>{grade.teacherName}</td>
                <td>
                  {grade.finalLetter ?? '—'}
                  {grade.finalPct != null && <span className="grade-pct"> ({grade.finalPct}%)</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
