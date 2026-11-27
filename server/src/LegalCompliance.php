<?php
declare(strict_types=1);

/** Narrow statutory baseline, not a transaction classification or issuance authorization.
 * Caller must establish covered domestic B2B scope and historical subject status.
 */
final class LegalCompliance {
    public static function evaluate(string $date, string $subject, string $scope): array {
        if (!preg_match('/\A[0-9]{4}-[0-9]{2}-[0-9]{2}\z/D', $date)
            || !checkdate((int)substr($date,5,2),(int)substr($date,8,2),(int)substr($date,0,4))) {
            throw new InvalidArgumentException('Invalid effective date.');
        }
        if (!in_array($subject,['VAT','NON_VAT_COVERED','UNKNOWN'],true)
            || !in_array($scope,['STANDARD_DOMESTIC_B2B','UNRESOLVED'],true)) {
            throw new InvalidArgumentException('Invalid legal context.');
        }
        $version = $date >= '2026-01-01' && $date < '2027-01-01' ? 'HR-F2-2026-v1'
            : ($date >= '2027-01-01' && $date < '2028-01-01' ? 'HR-F2-2027-v1' : null);
        $issue = $receive = 'REVIEW_REQUIRED';
        if ($version !== null && $scope === 'STANDARD_DOMESTIC_B2B' && $subject !== 'UNKNOWN') {
            $receive = 'REQUIRED';
            $issue = $subject === 'VAT' || $version === 'HR-F2-2027-v1'
                ? 'REQUIRED' : 'NOT_REQUIRED_BY_THIS_RULE';
        }
        return ['version'=>$version,'issue'=>$issue,'receive'=>$receive,
            'fiscalizeIssue'=>$issue,'fiscalizeReceive'=>$receive];
    }
}
