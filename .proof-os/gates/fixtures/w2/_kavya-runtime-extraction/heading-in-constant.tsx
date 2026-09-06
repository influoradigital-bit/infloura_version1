import React from 'react';

const FALLBACK_HEADING = "Something went wrong";

export default class ErrorBoundary extends React.Component {
  render() {
    if (this.state.hasError) {
      return (
        <div>
          <h1>{FALLBACK_HEADING}</h1>
          <button>Try again</button>
          <button>Reload page</button>
        </div>
      );
    }
    return this.props.children;
  }
}
