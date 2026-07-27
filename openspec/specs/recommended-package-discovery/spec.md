## Purpose

Defines a hardcoded, readable-name list of official Lua packages surfaced in package management above the repository-URL input field, where selecting an entry launches the existing repository-resolution and installation flow.

## Requirements

### Requirement: Recommended official packages are surfaced above the URL field
The package-management install surface SHALL display a hardcoded, ordered list of recommended official packages immediately above the canonical-repository-URL input field. Each entry SHALL display a human-readable package name and SHALL NOT display a repository URL. Each entry SHALL carry its canonical GitHub repository URL internally for resolution. The list SHALL be static and SHALL render without any network access. Selecting an entry SHALL invoke the existing repository-resolution entry point with that entry's canonical URL; release selection, trust confirmation, and installation SHALL then proceed through the existing package-management flow unchanged.

#### Scenario: Recommendations render without network
- **WHEN** the user opens the package-management install surface
- **THEN** the recommended list SHALL display its entries using readable names only
- **AND** no repository URL SHALL be shown for any entry
- **AND** rendering the list SHALL NOT require a network request

#### Scenario: Selecting a recommendation starts resolution
- **WHEN** the user selects a recommended package entry
- **THEN** the host SHALL invoke the existing repository-resolution flow with that entry's canonical URL
- **AND** the subsequent release-selection, trust-confirmation, and installation steps SHALL be identical to resolving the same URL manually

#### Scenario: Recommendations are static
- **WHEN** the application is built
- **THEN** the recommended list SHALL be a fixed in-application list
- **AND** it SHALL NOT be fetched from a remote manifest at runtime

### Requirement: Recommended list contains the official channel packages
The recommended list SHALL contain the official Journal, Keyboard, OpenAI Agent, Debug, and Diagnostics packages, each mapped to its canonical repository. The Journal, Keyboard, and OpenAI Agent entries replace removed built-in channels. The Debug and Diagnostics entries are recommended companions and do not replace a built-in channel.

#### Scenario: List contains the five official packages
- **WHEN** the recommended list is rendered
- **THEN** it SHALL include Journal (`talkcan-channels/journal`), Keyboard (`talkcan-channels/keyboard`), OpenAI Agent (`talkcan-channels/openai-agent`), Debug (`talkcan-channels/debug`), and Diagnostics (`talkcan-channels/diagnostics`), each with its readable display name and canonical repository URL

#### Scenario: Recommended list is independent of installed state
- **WHEN** one or more recommended packages are already installed
- **THEN** the recommended list SHALL still display every entry
- **AND** it SHALL NOT hide or filter installed packages
