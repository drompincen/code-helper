# System Architecture

## Overview

The platform follows a microservices architecture with event-driven communication
between bounded contexts.

## Core Services

### Onboarding Service
Manages the customer onboarding lifecycle including KYC verification,
document collection, and approval workflows. The onboarding case state
machine drives the process from application through to account activation.

### Compliance Engine
Monitors ongoing customer activity for regulatory compliance. This includes
material change detection, periodic reviews, and sanctions screening.

### Transaction Service
Handles financial transaction processing with real-time validation,
execution, and settlement. Supports multiple payment rails and currencies.

## Event Architecture

Services communicate via domain events published to a message broker.
Key event flows:
- Onboarding completion triggers account provisioning
- Material changes trigger compliance reviews
- Transaction patterns feed into risk scoring

## Data Storage

Each service owns its data store:
- Onboarding: PostgreSQL for case management
- Compliance: DuckDB for analytical queries
- Transactions: PostgreSQL with partitioned tables for high throughput
