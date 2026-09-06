import React from 'react';

export default class ErrorBoundary extends React.Component {
  render() {
    if (this.state.hasError) {
      return (
        <div>
          <p className="error-message">Something went wrong</p>
          <button>Try again</button>
          <button>Reload page</button>
        </div>
      );
    }
    return this.props.children;
  }
}
