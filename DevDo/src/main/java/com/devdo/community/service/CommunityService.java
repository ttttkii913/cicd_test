package com.devdo.community.service;

import com.devdo.common.error.ErrorCode;
import com.devdo.common.exception.BusinessException;
import com.devdo.community.controller.dto.request.CommunityRequestDto;
import com.devdo.community.entity.Community;
import com.devdo.community.repository.CommunityRepository;
import com.devdo.member.domain.Member;
import com.devdo.member.domain.repository.MemberRepository;
import com.devdo.scrap.repository.ScrapRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.time.*;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CommunityService {

    private final CommunityRepository communityRepository;
    private final MemberRepository memberRepository;
    private final ScrapRepository scrapRepository;
    private final StringRedisTemplate stringRedisTemplate;

    // 공통 메서드
    @Transactional
    public Community findCommunityById(Long id) {
        return communityRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMUNITY_NOT_FOUND_EXCEPTION
                        , ErrorCode.COMMUNITY_NOT_FOUND_EXCEPTION.getMessage() + id));
    }

    @Transactional(readOnly = true)
    public Member findMemberById(Long id) {
        return memberRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND_EXCEPTION
                        , ErrorCode.MEMBER_NOT_FOUND_EXCEPTION.getMessage() + id));
    }

    @Transactional(readOnly = true)
    public Member getMemberFromPrincipal(Principal principal) {
        if (principal == null || principal.getName() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN_EXCEPTION
                    , ErrorCode.FORBIDDEN_EXCEPTION.getMessage());
        }
        try {
            Long memberId = Long.parseLong(principal.getName());
            return findMemberById(memberId);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.FORBIDDEN_EXCEPTION
                    , ErrorCode.FORBIDDEN_EXCEPTION.getMessage());
        }
    }

    // 주요 서비스 메서드
    @Transactional
    public Long createCommunity(CommunityRequestDto commnuityRequestDto, Principal principal) {
        Member member = getMemberFromPrincipal(principal);

        Community community = Community.builder()
                .title(commnuityRequestDto.title())
                .content(commnuityRequestDto.content())
                .member(member)
                .build();

        return communityRepository.save(community).getId();
    }

    @Transactional
    public Community updateCommunity(Long communityId, CommunityRequestDto commnuityRequestDto, Principal principal) {
        Community community = findCommunityById(communityId);
        Member member = getMemberFromPrincipal(principal);

        if (!community.getMember().getMemberId().equals(member.getMemberId())) {
            throw new BusinessException(ErrorCode.NO_AUTHORIZATION_EXCEPTION, ErrorCode.NO_AUTHORIZATION_EXCEPTION.getMessage());
        }

        community.update(commnuityRequestDto);
        return community;
    }

    @Transactional
    public void deleteCommunity(Long communityId, Principal principal) {
        Community community = findCommunityById(communityId);
        Member member = getMemberFromPrincipal(principal);

        if (!community.getMember().getMemberId().equals(member.getMemberId())) {
            throw new BusinessException(ErrorCode.NO_AUTHORIZATION_EXCEPTION, ErrorCode.NO_AUTHORIZATION_EXCEPTION.getMessage());
        }

        // Scrap 먼저 삭제
        scrapRepository.deleteAllByCommunity(community);

        communityRepository.delete(community);
    }

    @Transactional(readOnly = true)
    public Community getCommunity(Long id) {
        return communityRepository.findWithMemberById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMUNITY_NOT_FOUND_EXCEPTION, ErrorCode.COMMUNITY_NOT_FOUND_EXCEPTION.getMessage() + id));
    }

    @Transactional(readOnly = true)
    public List<Community> getAllCommunities() {
        return communityRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Community> getMyCommunities(Principal principal) {
        Member member = getMemberFromPrincipal(principal);
        return communityRepository.findAllByMember_MemberId(member.getMemberId());
    }

    @Transactional(readOnly = true)
    public List<Community> searchCommunitiesByTitle(String keyword) {
        return communityRepository.findByTitleContainingIgnoreCase(keyword);
    }

    // 커뮤니티 게시글 조회수 쿠키 기반 반영
    /*@Transactional
    public Community getCommunityWithViewCount(Long communityId, HttpServletRequest request, HttpServletResponse response) {
        Community community = getCommunity(communityId);

        // 쿠키 가져오기
        Cookie[] cookies = Optional.ofNullable(request.getCookies()).orElseGet(() -> new Cookie[0]);

        // 쿠키 검사
        Cookie cookie = Arrays.stream(cookies)
                .filter(c -> c.getName().equals("communityView"))
                .findFirst()
                .orElseGet(() -> {
                    // communityView 라는 쿠키가 없다면 24시간 동안 처음 접근하는 것이기에 조회수 1 증가
                    community.increaseViewCount();
                    // communityView 라는 이름의 게시글 id 쿠키 생성
                    return new Cookie("communityView", "[" + community.getId() + "]");
                });

        // 기존 communityView 쿠키에 다른 게시글도 조회시 추가 [1][2] .. 로 쌓임
        if (!cookie.getValue().contains("[" + community.getId() + "]")) {
            community.increaseViewCount();
            cookie.setValue(cookie.getValue() + "[" + community.getId() + "]");
        }

        // 현재를 기준으로 자정까지 남은 시간 계산
        long todayEndSecond = LocalDate.now().atTime(LocalTime.MAX).toEpochSecond(ZoneOffset.UTC);
        long currentSecond = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);

        cookie.setPath("/");
        cookie.setMaxAge((int) (todayEndSecond - currentSecond));
        response.addCookie(cookie);

        return community;
    }*/

    // redis 기반 구현
    @Transactional
    public Community getCommunityWithRedisViewCount(Long communityId, Principal principal) {
        Community community = getCommunity(communityId);
        String memberId = principal.getName();

        String redisKey = "community:view:" + memberId + ":" + communityId;

        Boolean hasViewed = stringRedisTemplate.hasKey(redisKey);
        if (hasViewed == null || !hasViewed) {
            community.increaseViewCount();

            // 24시간 후 만료
            stringRedisTemplate.opsForValue().set(redisKey, "1", Duration.ofMinutes(1));
        }
        System.out.println("Redis 조회 여부: " + hasViewed);
        System.out.println("현재 게시글 조회수: " + community.getViewCount());

        return community;
    }
}
