# Platform Services

A comprehensive financial services platform for customer onboarding,
compliance monitoring, and transaction processing.

## Getting Started

1. Set up PostgreSQL database
2. Configure application properties
3. Run the application: `./gradlew bootRun`

## Features

- **Customer Onboarding**: Automated KYC workflows with multi-stage approvals
- **Compliance Monitoring**: Real-time material change detection and review triggers
- **Transaction Processing**: High-throughput payment processing with settlement

## API Reference

### Onboarding API
- `POST /api/onboarding/cases` - Create new onboarding case
- `GET /api/onboarding/cases/{id}` - Get case status
- `PUT /api/onboarding/cases/{id}/approve` - Approve onboarding case

### Compliance API
- `GET /api/compliance/reviews` - List pending reviews
- `POST /api/compliance/reviews/{id}/complete` - Complete a review

### Transaction API
- `POST /api/transactions` - Submit a transaction
- `GET /api/transactions/{id}/status` - Check transaction status
