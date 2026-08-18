// KNOWN-GOOD 05 — a capitalized binding declared inside a render body AND mounted as JSX, which
// is nevertheless correct: it is an ALIAS of a component that already exists elsewhere, not a new
// function literal. `const StageIcon = stage.icon` is exactly what the real brand-pipeline page
// does at line 471, so a detector that keys on "capitalized + declared in render + mounted" alone
// reds the live tree. This fixture is the reason the analyser requires a function/class LITERAL.
import React from 'react';

const IconA = () => <svg />;
const IconB = () => <svg />;

const stages = [
  { id: 'a', icon: IconA },
  { id: 'b', icon: IconB },
];

const registry: Record<string, React.ComponentType> = { a: IconA, b: IconB };

export default function PipelinePage() {
  const [sel, setSel] = React.useState('a');
  const Selected = registry[sel];
  const Fallback = IconA;

  return (
    <div>
      <button onClick={() => setSel('b')}>next</button>
      <Selected />
      <Fallback />
      {stages.map((stage) => {
        const StageIcon = stage.icon;
        return (
          <div key={stage.id}>
            <StageIcon />
          </div>
        );
      })}
    </div>
  );
}
